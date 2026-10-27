package com.example.metronome.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.Process
import com.example.metronome.data.Language
import com.example.metronome.data.SoundType
import com.example.metronome.data.TimeSignature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * 节拍器引擎参数。
 * @param bpm 每分钟拍数（复合拍号时为附点四分音符数/分钟），范围 30..220。
 * @param masterVolume 主音量 0..1，运行时实时调节（不触发缓冲重建）。
 * @param language UI 语言，决定 MALE/FEMALE 人声采用中文还是英文语音文件。
 */
data class MetronomeParams(
    val bpm: Int,
    val timeSignature: TimeSignature,
    val sound: SoundType,
    val accentEnabled: Boolean,
    val masterVolume: Float,
    val language: Language,
)

/** 节拍事件：当前进入的拍序号（0=重拍）及是否重拍。 */
data class BeatEvent(val beatIndex: Int, val isAccent: Boolean)

/** 引擎状态：播放状态 + 当前参数。 */
data class EngineState(
    val status: PlaybackStatus,
    val bpm: Int,
    val timeSignature: TimeSignature,
    val sound: SoundType,
    val accentEnabled: Boolean,
    val masterVolume: Float,
)

enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

/**
 * AudioTrack 节拍引擎（零漂移、低延迟核心）。
 *
 * 零漂移原理：
 *  - MODE_STATIC：整小节 PCM 一次性写入；每小节采样数为整数（samplesPerBeat×beatCount）。
 *  - setLoopPoints(0, barTotal, -1)：以硬件采样率循环，循环长度为整数采样数 → 长时间运行无累积漂移。
 *  - 视觉同步：用 AudioTimestamp(framePosition,nanoTime) 锚点 + 时间外推计算当前拍序号，
 *    与音频输出同一时钟基准，避免 getPlaybackHeadPosition 的粒度/停滞问题。
 *
 * 延迟控制：
 *  - 采样率取设备原生率（AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE），避免重采样。
 *  - AudioAttributes USAGE_GAME + PERFORMANCE_MODE_LOW_LATENCY，追求 <20ms 首拍延迟。
 *  - 若低延迟轨道拒绝 setLoopPoints，回退 PERFORMANCE_MODE_NONE。
 */
class MetronomeEngine(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 人声 WAV 样本缓存：key = "male_zh_1" 等。assets 不变，命中即复用，避免重复解码。 */
    private val voiceCache = HashMap<String, DoubleArray>()

    /** 设备原生输出采样率，避免重采样以降低延迟。 */
    private val sampleRate: Int =
        runCatching {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull() ?: DEFAULT_SAMPLE_RATE

    // ---- 增益配置（相对，主音量由 track.setVolume 实时调节）----
    private val accentGain = 1.0   // 重拍
    private val weakGain = 0.55    // 弱拍（重拍开启时）
    private val flatGain = 0.8     // 全部等响（重拍关闭时）
    private val globalScale = 0.9  // 相对 1.0 归一值的整体缩放，留出余量避免削波

    // ---- 当前缓冲与几何 ----
    @Volatile private var buffer: ShortArray = ShortArray(0)
    @Volatile private var barTotal: Int = 0
    @Volatile private var samplesPerBeat: Int = 0
    @Volatile private var beatCount: Int = 0

    @Volatile private var track: AudioTrack? = null
    @Volatile private var params: MetronomeParams

    private val _state: MutableStateFlow<EngineState>
    val state: StateFlow<EngineState> get() = _state.asStateFlow()

    private val _beat = MutableSharedFlow<BeatEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val beatFlow: Flow<BeatEvent> = _beat.asSharedFlow()
        .distinctUntilChanged { a, b -> a.beatIndex == b.beatIndex }

    init {
        params = MetronomeParams(
            bpm = 120,
            timeSignature = TimeSignature.TIME_4_4,
            sound = SoundType.SINE,
            accentEnabled = true,
            masterVolume = 0.7f,
            language = Language.ZH,
        )
        _state = MutableStateFlow(EngineState(PlaybackStatus.IDLE, params.bpm, params.timeSignature, params.sound, params.accentEnabled, params.masterVolume))
        rebuildBufferLocked()
    }

    /** 配置参数。若正在播放/暂停则重建轨道并恢复原播放状态；IDLE 仅重建缓冲。 */
    @Synchronized
    fun configure(newParams: MetronomeParams) {
        params = newParams
        rebuildBufferLocked()
        when (_state.value.status) {
            PlaybackStatus.PLAYING -> {
                rebuildTrackAndPlay()
            }
            PlaybackStatus.PAUSED -> {
                // 重建轨道但保持暂停
                releaseTrackInternal()
                ensureTrackBuilt()
                // track 已构建但未 play
            }
            PlaybackStatus.IDLE -> Unit
        }
        publishState()
    }

    /** 仅实时调节主音量，不重建缓冲（零开销、零间隙）。 */
    @Synchronized
    fun setMasterVolume(v: Float) {
        params = params.copy(masterVolume = v.coerceIn(0f, 1f))
        track?.setVolume(params.masterVolume)
        publishState()
    }

    @Synchronized
    fun play() {
        when (_state.value.status) {
            PlaybackStatus.IDLE -> {
                ensureTrackBuilt()
                startPolling()
                track?.play()
                updateStatus(PlaybackStatus.PLAYING)
            }
            PlaybackStatus.PAUSED -> {
                track?.play()
                updateStatus(PlaybackStatus.PLAYING)
            }
            PlaybackStatus.PLAYING -> Unit
        }
    }

    @Synchronized
    fun pause() {
        if (_state.value.status == PlaybackStatus.PLAYING) {
            track?.pause()
            updateStatus(PlaybackStatus.PAUSED)
        }
    }

    @Synchronized
    fun stop() {
        stopPolling()
        releaseTrackInternal()
        updateStatus(PlaybackStatus.IDLE)
    }

    @Synchronized
    fun release() {
        stop()
    }

    // ---- 内部实现 ----

    private fun publishState() {
        val p = params
        _state.value = EngineState(_state.value.status, p.bpm, p.timeSignature, p.sound, p.accentEnabled, p.masterVolume)
    }

    private fun updateStatus(s: PlaybackStatus) {
        _state.value = _state.value.copy(status = s)
    }

    /** 根据当前参数重建整小节 PCM 缓冲。 */
    private fun rebuildBufferLocked() {
        val p = params
        beatCount = p.timeSignature.beatCount
        samplesPerBeat = (60.0 / p.bpm * sampleRate).roundToInt().coerceAtLeast(1)
        barTotal = (beatCount * samplesPerBeat).coerceAtLeast(1)

        val bar = DoubleArray(barTotal)
        val isVoice = p.sound == SoundType.MALE || p.sound == SoundType.FEMALE
        if (isVoice) {
            // 人声节拍：每拍放置对应数字的语音样本，覆写（不混合），按拍槽截断避免覆盖下一拍。
            // 中文：一二三四；英文：one two three four。beatNumber 1=重拍。
            for (i in 0 until beatCount) {
                val beatNumber = i + 1
                val voice = loadVoiceBeat(p.sound, p.language, beatNumber)
                    ?: WaveformSynthesizer.generateClick(SoundType.SINE, sampleRate) // 文件缺失回退正弦
                val gain = if (p.accentEnabled) { if (i == 0) accentGain else weakGain } else flatGain
                val offset = i * samplesPerBeat
                val maxJ = minOf(voice.size, samplesPerBeat, barTotal - offset)
                for (j in 0 until maxJ) {
                    bar[offset + j] = (voice[j] * gain).coerceIn(-1.0, 1.0)
                }
            }
        } else {
            // 合成音色：短瞬态，混合叠加
            for (i in 0 until beatCount) {
                val isAccent = p.accentEnabled && i == 0
                val gain = if (p.accentEnabled) { if (i == 0) accentGain else weakGain } else flatGain
                val click = WaveformSynthesizer.generateClick(p.sound, sampleRate)
                val offset = i * samplesPerBeat
                val maxJ = minOf(click.size, barTotal - offset)
                for (j in 0 until maxJ) {
                    bar[offset + j] = (bar[offset + j] + click[j] * gain).coerceIn(-1.0, 1.0)
                }
            }
        }

        buffer = ShortArray(barTotal) { idx ->
            (bar[idx] * globalScale * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    // ---- 人声 WAV 加载 ----

    /** 加载某次拍的人声样本（按性别/语言/拍号），带缓存。文件缺失返回 null（调用方回退合成音）。 */
    private fun loadVoiceBeat(gender: SoundType, language: Language, beatNumber: Int): DoubleArray? {
        val g = if (gender == SoundType.MALE) "male" else "female"
        val lang = language.code // "zh" / "en"
        val key = "${g}_${lang}_$beatNumber"
        voiceCache[key]?.let { return it }
        val path = "voice/${g}_${lang}_$beatNumber.wav"
        val decoded = runCatching {
            appContext.assets.open(path).use { decodeWavPcm16(it) }
        }.getOrNull() ?: return null
        voiceCache[key] = decoded
        return decoded
    }

    /**
     * 解码 WAV（仅 16-bit PCM；单/立体声→单声道；任意采样率→线性重采样到引擎 sampleRate）。
     * 返回归一化 [-1,1] 的 DoubleArray。损坏/不支持的格式返回空数组（调用方回退）。
     */
    private fun decodeWavPcm16(input: InputStream): DoubleArray {
        val data = input.readBytes()
        if (data.size < 12) return DoubleArray(0)
        var pos = 12 // 跳过 "RIFF" + size + "WAVE"
        var audioFormat = 1; var channels = 1; var srcRate = sampleRate; var bits = 16
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= data.size) {
            val chunkId = runCatching { String(data, pos, 4, Charsets.US_ASCII) }.getOrDefault("")
            val chunkSize = readLeInt(data, pos + 4)
            when (chunkId) {
                "fmt " -> {
                    audioFormat = readLeShort(data, pos + 8)
                    channels = readLeShort(data, pos + 10)
                    srcRate = readLeInt(data, pos + 12)
                    bits = readLeShort(data, pos + 22)
                }
                "data" -> {
                    dataStart = pos + 8
                    dataLen = chunkSize
                }
            }
            pos += 8 + chunkSize + (chunkSize and 1) // 偶对齐
            if (chunkId == "data") break
        }
        if (dataStart < 0 || audioFormat != 1 || bits != 16) return DoubleArray(0)
        val bytes = minOf(dataLen, data.size - dataStart).coerceAtLeast(0)
        val frameCount = bytes / (2 * channels)
        if (frameCount <= 0) return DoubleArray(0)
        val raw = DoubleArray(frameCount)
        var di = dataStart
        for (i in 0 until frameCount) {
            var sum = 0.0
            for (c in 0 until channels) {
                if (di + 1 >= data.size) break
                val lo = data[di].toInt() and 0xFF
                val hi = data[di + 1].toInt()
                val s = (hi shl 8) or lo // 16-bit 有符号
                sum += s / 32768.0
                di += 2
            }
            raw[i] = sum / channels
        }
        return if (srcRate == sampleRate) raw else linearResample(raw, srcRate, sampleRate)
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** 线性插值重采样（srcRate→dstRate）。 */
    private fun linearResample(src: DoubleArray, srcRate: Int, dstRate: Int): DoubleArray {
        if (src.isEmpty() || srcRate <= 0) return DoubleArray(0)
        val ratio = dstRate.toDouble() / srcRate
        val outLen = (src.size * ratio).toInt().coerceAtLeast(1)
        val out = DoubleArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = src[idx]
            val s1 = if (idx + 1 < src.size) src[idx + 1] else src[idx]
            out[i] = s0 + (s1 - s0) * frac
        }
        return out
    }

    /** 构建 AudioTrack 并写入静态缓冲 + 设置循环点。低延迟失败时回退到普通模式。 */
    private fun ensureTrackBuilt() {
        if (track != null) return
        val t = buildTrack(performanceLowLatency = true)
        val written = t.write(buffer, 0, barTotal, AudioTrack.WRITE_BLOCKING)
        if (written != barTotal) {
            // 静态写入失败，回退普通模式重建
            t.release()
            val t2 = buildTrack(performanceLowLatency = false)
            t2.write(buffer, 0, barTotal, AudioTrack.WRITE_BLOCKING)
            applyLoopAndAssign(t2)
        } else {
            applyLoopAndAssign(t)
        }
        track?.setVolume(params.masterVolume)
    }

    private fun applyLoopAndAssign(t: AudioTrack) {
        val r = t.setLoopPoints(0, barTotal, -1)
        if (r != AudioTrack.SUCCESS) {
            // 低延迟轨道拒绝循环：回退普通模式
            t.release()
            val t2 = buildTrack(performanceLowLatency = false)
            t2.write(buffer, 0, barTotal, AudioTrack.WRITE_BLOCKING)
            t2.setLoopPoints(0, barTotal, -1)
            track = t2
            t2.setVolume(params.masterVolume)
        } else {
            track = t
        }
    }

    private fun buildTrack(performanceLowLatency: Boolean): AudioTrack {
        val bufferSize = barTotal * 2 // 16-bit mono
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setPerformanceMode(
                if (performanceLowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                else AudioTrack.PERFORMANCE_MODE_NONE
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }

    /** 播放中更换参数时：释放旧轨道、构建新轨道并继续播放。 */
    private fun rebuildTrackAndPlay() {
        stopPolling()
        releaseTrackInternal()
        ensureTrackBuilt()
        startPolling()
        track?.play()
    }

    private fun releaseTrackInternal() {
        track?.runCatching {
            stop()
            release()
        }
        track = null
    }

    // ---- 视觉同步轮询线程 ----
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    private fun startPolling() {
        if (pollThread?.isAlive == true) {
            polling = true
            return
        }
        polling = true
        pollThread = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_e: Exception) {
            }
            var lastBeat = -1
            while (polling) {
                try {
                    val tr = track
                    if (tr != null && _state.value.status == PlaybackStatus.PLAYING) {
                        val frame = currentFrame(tr)
                        if (frame >= 0) {
                            val beat = ((frame % barTotal) / samplesPerBeat.coerceAtLeast(1)).toInt()
                            if (beat != lastBeat) {
                                lastBeat = beat
                                _beat.tryEmit(BeatEvent(beat, params.accentEnabled && beat == 0))
                            }
                        }
                    }
                } catch (_e: Exception) {
                }
                try { Thread.sleep(POLL_INTERVAL_MS) } catch (_e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = try { pollThread?.apply { join(200) } } catch (_e: Exception) { null }
        pollThread = null
    }

    /**
     * 计算当前播放到的采样帧位置。
     * 优先用 AudioTimestamp 锚点 + System.nanoTime 外推（亚毫秒抖动）；
     * 若时间戳无效/过旧（暂停后首帧可能返回过期锚点），回退 getPlaybackHeadPosition。
     */
    private fun currentFrame(tr: AudioTrack): Long {
        val ts = AudioTimestamp()
        val now = System.nanoTime()
        if (tr.getTimestamp(ts) && ts.nanoTime > 0L && now >= ts.nanoTime) {
            val dtNanos = now - ts.nanoTime
            // 防止使用过期锚点（暂停/恢复初期）外推溢出
            if (dtNanos < STALE_THRESHOLD_NANOS) {
                val dtSec = dtNanos / 1e9
                return ts.framePosition + (dtSec * sampleRate).toLong()
            }
        }
        @Suppress("DEPRECATION")
        return tr.playbackHeadPosition.toLong()
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val POLL_INTERVAL_MS = 15L // ~66Hz 轮询，足够覆盖每拍闪烁
        private const val STALE_THRESHOLD_NANOS = 1_000_000_000L // 1s
    }
}
