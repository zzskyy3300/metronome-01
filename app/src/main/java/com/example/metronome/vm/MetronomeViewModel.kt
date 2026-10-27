package com.example.metronome.vm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.metronome.audio.MetronomeParams
import com.example.metronome.audio.PlaybackStatus
import com.example.metronome.data.Language
import com.example.metronome.data.SettingsRepository
import com.example.metronome.data.SoundType
import com.example.metronome.data.TimeSignature
import com.example.metronome.service.MetronomeService
import com.example.metronome.service.PlaybackState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** UI 状态：镜像服务 PlaybackState + Tap Tempo 临时 BPM + 语言。 */
data class UiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val bpm: Int = 120,
    val timeSignature: TimeSignature = TimeSignature.TIME_4_4,
    val sound: SoundType = SoundType.SINE,
    val accentEnabled: Boolean = true,
    val masterVolume: Float = 0.7f,
    val currentBeat: Int = 0,
    val practiceMinutes: Int = 0,
    val practiceRemainingMs: Long = 0L,
    val practiceActive: Boolean = false,
    val tapBpm: Int? = null,
    val language: Language = Language.ZH,
)

/**
 * 节拍器 ViewModel（MVVM 极简）。
 *
 * 职责：
 * - 绑定 [MetronomeService]，聚合其 [PlaybackState] 为 [UiState] 供 Compose 观察。
 * - 转发用户命令到服务（Play 经 startForegroundService，其余经 Binder）。
 * - Tap Tempo：收集最近若干次点击间隔，平均换算 BPM（30..220）。
 * - 参数变更即时写回 [SettingsRepository]（持久化）。
 * - 语言切换：写回 prefs 并发出 recreate 信号，由 MainActivity 重建以应用新 Locale。
 */
class MetronomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private var binder: MetronomeService.LocalBinder? = null

    private val _ui = MutableStateFlow(UiState(
        bpm = repo.bpm,
        timeSignature = repo.timeSignature,
        sound = repo.sound,
        accentEnabled = repo.accentEnabled,
        masterVolume = repo.volume,
        practiceMinutes = repo.practiceMinutes,
        language = repo.language,
    ))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _recreateSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recreateSignal: SharedFlow<Unit> = _recreateSignal.asSharedFlow()

    // ---- 服务绑定 ----
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? MetronomeService.LocalBinder
            // 初次连接：把当前持久化参数下发到服务（构建初始缓冲）
            binder?.configure(currentParams(), _ui.value.practiceMinutes)
            // 收集服务状态
            viewModelScope.launch {
                binder?.state?.collectLatest { ps -> mapToUi(ps) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    init {
        // 绑定服务（BIND_AUTO_CREATE），保持跨配置变化（如语言切换重建）的状态连续
        val ctx = getApplication<Application>()
        ctx.bindService(
            Intent(ctx, MetronomeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onCleared() {
        super.onCleared()
        runCatching {
            getApplication<Application>().unbindService(connection)
        }
    }

    // ---- 命令 ----

    fun play() {
        MetronomeService.startPlayback(getApplication())
    }

    fun pause() {
        binder?.pause()
    }

    fun resume() {
        binder?.resume()
    }

    fun stop() {
        binder?.stop()
    }

    fun setBpm(value: Int) {
        val clamped = value.coerceIn(30, 220)
        repo.bpm = clamped
        _ui.value = _ui.value.copy(bpm = clamped)
        binder?.configure(currentParams(), _ui.value.practiceMinutes)
    }

    fun setTimeSignature(ts: TimeSignature) {
        repo.timeSignature = ts
        _ui.value = _ui.value.copy(timeSignature = ts)
        binder?.configure(currentParams(), _ui.value.practiceMinutes)
    }

    fun setSound(s: SoundType) {
        repo.sound = s
        _ui.value = _ui.value.copy(sound = s)
        binder?.configure(currentParams(), _ui.value.practiceMinutes)
    }

    fun setVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        repo.volume = clamped
        _ui.value = _ui.value.copy(masterVolume = clamped)
        binder?.setVolume(clamped)
    }

    fun toggleAccent() {
        val newValue = !_ui.value.accentEnabled
        repo.accentEnabled = newValue
        _ui.value = _ui.value.copy(accentEnabled = newValue)
        binder?.configure(currentParams(), _ui.value.practiceMinutes)
    }

    fun setPracticeMinutes(m: Int) {
        val clamped = m.coerceIn(0, 180)
        repo.practiceMinutes = clamped
        _ui.value = _ui.value.copy(
            practiceMinutes = clamped,
            practiceRemainingMs = if (clamped == 0) 0L else clamped.toLong() * 60_000L
        )
        binder?.configure(currentParams(), clamped)
    }

    fun toggleLanguage() {
        val newLang = if (_ui.value.language == Language.ZH) Language.EN else Language.ZH
        repo.language = newLang
        _ui.value = _ui.value.copy(language = newLang)
        // 立即下发新语言，引擎据此切换男声/女声的 zh/en 语音文件
        binder?.configure(currentParams(), _ui.value.practiceMinutes)
        _recreateSignal.tryEmit(Unit)
    }

    // ---- Tap Tempo ----
    private val tapTimes = ArrayDeque<Long>()
    fun tapTempo() {
        val now = System.nanoTime()
        // 清理超过 2.5s 的旧点击
        while (tapTimes.isNotEmpty() && now - (tapTimes.first() ?: now) > TAP_WINDOW_NS) {
            tapTimes.removeFirst()
        }
        tapTimes.addLast(now)
        if (tapTimes.size < 2) {
            _ui.value = _ui.value.copy(tapBpm = null)
            return
        }
        val intervals = tapTimes.toList().zipWithNext { a, b -> b - a }
        val avg = intervals.average()
        if (avg <= 0.0) return
        var bpm = (60_000_000_000.0 / avg).toInt()
        if (bpm < 30) bpm = 30
        if (bpm > 220) bpm = 220
        _ui.value = _ui.value.copy(tapBpm = bpm)
        setBpm(bpm)
    }

    // ---- 辅助 ----
    private fun currentParams(): MetronomeParams = MetronomeParams(
        bpm = _ui.value.bpm,
        timeSignature = _ui.value.timeSignature,
        sound = _ui.value.sound,
        accentEnabled = _ui.value.accentEnabled,
        masterVolume = _ui.value.masterVolume,
        language = _ui.value.language,
    )

    private fun mapToUi(ps: PlaybackState) {
        _ui.value = _ui.value.copy(
            status = ps.status,
            bpm = ps.bpm,
            timeSignature = ps.timeSignature,
            sound = ps.sound,
            accentEnabled = ps.accentEnabled,
            masterVolume = ps.masterVolume,
            currentBeat = ps.currentBeat,
            practiceMinutes = ps.practiceMinutes,
            practiceRemainingMs = ps.practiceRemainingMs,
            practiceActive = ps.practiceActive,
        )
    }

    companion object {
        private const val TAP_WINDOW_NS = 2_500_000_000L // 2.5s
    }
}
