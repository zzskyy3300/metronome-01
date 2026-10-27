package com.example.metronome.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.metronome.MainActivity
import com.example.metronome.R
import com.example.metronome.audio.MetronomeEngine
import com.example.metronome.audio.MetronomeParams
import com.example.metronome.audio.PlaybackStatus
import com.example.metronome.data.SoundType
import com.example.metronome.data.TimeSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI 观察的播放状态。 */
data class PlaybackState(
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
)

/**
 * 前台服务：息屏/后台持续播放节拍 + 状态栏通知（暂停/继续/停止）。
 *
 * 关键点：
 * - 类型化前台服务 mediaPlayback（API34 必需），通过 Service.startForeground(id,notif,type) 传入类型标志（API29+），低版本退化为无类型重载。
 * - 命令双通道：通知按钮（跨进程）→ PendingIntent → onStartCommand(action)；
 *   ViewModel（同进程）→ Binder 直调；二者汇聚到 [applyPlay]/[applyPause]/[applyStop]/[applyConfigure]。
 * - 通知随状态切换 Pause↔Resume 主按钮，通过 NotificationManager.notify 更新（不重复 startForeground）。
 * - 练习计时：播放期间倒计时，到点自动停止。
 */
class MetronomeService : Service() {

    private lateinit var engine: MetronomeEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // ---- 练习计时状态 ----
    private var practiceTotalMs: Long = 0L
    private var practiceElapsedMs: Long = 0L
    private var playStartElapsed: Long = 0L
    private val practiceTick = object : Runnable {
        override fun run() {
            if (_state.value.status == PlaybackStatus.PLAYING && _state.value.practiceActive) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = practiceElapsedMs + (now - playStartElapsed)
                val remaining = (practiceTotalMs - elapsed).coerceAtLeast(0L)
                _state.value = _state.value.copy(practiceRemainingMs = remaining)
                if (remaining <= 0L) {
                    // 练习结束：停止播放并更新通知
                    applyStop()
                    _state.value = _state.value.copy(
                        practiceActive = false,
                        practiceRemainingMs = 0L
                    )
                    return
                }
            }
            handler.postDelayed(this, 250L)
        }
    }

    inner class LocalBinder : Binder() {
        val service: MetronomeService get() = this@MetronomeService
        val state: StateFlow<PlaybackState> get() = this@MetronomeService.state
        fun configure(params: MetronomeParams, practiceMinutes: Int) = applyConfigure(params, practiceMinutes)
        fun setVolume(v: Float) = applySetVolume(v)
        fun pause() = applyPause()
        fun resume() = applyResume()
        fun stop() = applyStop()
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        engine = MetronomeEngine(this)
        createNotificationChannel()
        // 聚合引擎状态 → PlaybackState
        serviceScope.launch {
            engine.state.collect { es ->
                _state.value = _state.value.copy(
                    status = es.status,
                    bpm = es.bpm,
                    timeSignature = es.timeSignature,
                    sound = es.sound,
                    accentEnabled = es.accentEnabled,
                    masterVolume = es.masterVolume,
                )
                updateNotification()
            }
        }
        // 拍号闪烁：收集引擎 beatFlow → 更新当前拍
        serviceScope.launch {
            engine.beatFlow.collect { ev ->
                _state.value = _state.value.copy(currentBeat = ev.beatIndex)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> { applyPlay() }
            ACTION_PAUSE -> { applyPause() }
            ACTION_RESUME -> { applyResume() }
            ACTION_STOP -> { applyStop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(practiceTick)
        runCatching { serviceScope.cancel() }
        engine.release()
    }

    // ---- 命令实现（单点状态机）----

    fun applyPlay() {
        promoteForeground(buildNotification())
        engine.play()
        if (practiceTotalMs > 0L && _state.value.practiceActive.not()) {
            // 启动练习计时
            _state.value = _state.value.copy(practiceActive = true)
            practiceElapsedMs = 0L
            playStartElapsed = SystemClock.elapsedRealtime()
            handler.removeCallbacks(practiceTick)
            handler.post(practiceTick)
        } else if (practiceTotalMs > 0L && _state.value.practiceActive) {
            // 从暂停恢复计时
            playStartElapsed = SystemClock.elapsedRealtime()
            handler.removeCallbacks(practiceTick)
            handler.post(practiceTick)
        }
    }

    fun applyPause() {
        engine.pause()
        // 冻结练习计时
        if (_state.value.practiceActive) {
            val now = SystemClock.elapsedRealtime()
            practiceElapsedMs += (now - playStartElapsed)
            handler.removeCallbacks(practiceTick)
        }
    }

    fun applyResume() {
        engine.play()
        if (_state.value.practiceActive) {
            playStartElapsed = SystemClock.elapsedRealtime()
            handler.removeCallbacks(practiceTick)
            handler.post(practiceTick)
        }
    }

    fun applyStop() {
        engine.stop()
        handler.removeCallbacks(practiceTick)
        practiceElapsedMs = 0L
        _state.value = _state.value.copy(
            practiceRemainingMs = if (practiceTotalMs > 0L) practiceTotalMs else 0L,
            practiceActive = false
        )
        // 停止后取消前台并退出服务
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun applyConfigure(params: MetronomeParams, practiceMinutes: Int) {
        practiceTotalMs = practiceMinutes.toLong() * 60_000L
        if (practiceTotalMs == 0L) {
            _state.value = _state.value.copy(practiceActive = false, practiceRemainingMs = 0L)
            handler.removeCallbacks(practiceTick)
        } else if (_state.value.status == PlaybackStatus.IDLE) {
            _state.value = _state.value.copy(practiceMinutes = practiceMinutes, practiceRemainingMs = practiceTotalMs)
        }
        _state.value = _state.value.copy(practiceMinutes = practiceMinutes)
        engine.configure(params)
    }

    fun applySetVolume(v: Float) {
        engine.setMasterVolume(v)
    }

    // ---- 通知 ----

    private fun promoteForeground(notification: Notification) {
        // API34 类型化前台服务：显式传入 mediaPlayback 类型标志（API26-28 用 2 参重载，无类型但可启动）
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
    }

    private fun updateNotification() {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.notif_title))
            .setContentIntent(contentPendingIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        when (s.status) {
            PlaybackStatus.PLAYING -> {
                builder.setContentText(getString(R.string.notif_running, s.bpm, s.timeSignature.display))
                builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.pause), actionPendingIntent(ACTION_PAUSE))
            }
            PlaybackStatus.PAUSED -> {
                builder.setContentText(getString(R.string.notif_paused))
                builder.addAction(android.R.drawable.ic_media_play, getString(R.string.resume), actionPendingIntent(ACTION_RESUME))
            }
            PlaybackStatus.IDLE -> {
                builder.setContentText(getString(R.string.notif_paused))
            }
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), actionPendingIntent(ACTION_STOP))
        return builder.build()
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MetronomeService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notif_channel_desc) }
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "metronome_playback"
        const val NOTIF_ID = 1
        const val ACTION_PLAY = "com.example.metronome.action.PLAY"
        const val ACTION_PAUSE = "com.example.metronome.action.PAUSE"
        const val ACTION_RESUME = "com.example.metronome.action.RESUME"
        const val ACTION_STOP = "com.example.metronome.action.STOP"

        /** 启动服务进入前台并开始播放。ViewModel 通过此方法触发 Play。 */
        fun startPlayback(context: Context) {
            val intent = Intent(context, MetronomeService::class.java).apply { action = ACTION_PLAY }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
