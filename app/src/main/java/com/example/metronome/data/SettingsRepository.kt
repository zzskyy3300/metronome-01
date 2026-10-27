package com.example.metronome.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地参数持久化（SharedPreferences）。
 *
 * 保存：BPM、拍号、音色、音量、重拍开关、练习计时分钟数、语言。
 * 所有参数在 App 启动时读取并作为初始状态，用户修改后立即写回，保证重启后参数保持。
 *
 * 采用直接注入 SharedPreferences（仅 private 模式），无任何联网/同步。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 默认值 ----
    val defaultBpm: Int = 120
    val defaultTimeSignature: TimeSignature = TimeSignature.TIME_4_4
    val defaultSound: SoundType = SoundType.FEMALE
    val defaultVolume: Float = 0.7f
    val defaultAccent: Boolean = true
    val defaultPracticeMinutes: Int = 0 // 0 表示关闭练习计时
    val defaultLanguage: Language = Language.ZH

    var bpm: Int
        get() = prefs.getInt(KEY_BPM, defaultBpm).coerceIn(30, 220)
        set(value) = prefs.edit().putInt(KEY_BPM, value.coerceIn(30, 220)).apply()

    var timeSignature: TimeSignature
        get() = TimeSignature.entries.getOrElse(prefs.getInt(KEY_TS, defaultTimeSignature.ordinal)) { defaultTimeSignature }
        set(value) = prefs.edit().putInt(KEY_TS, value.ordinal).apply()

    var sound: SoundType
        get() = SoundType.entries.getOrElse(prefs.getInt(KEY_SOUND, defaultSound.ordinal)) { defaultSound }
        set(value) = prefs.edit().putInt(KEY_SOUND, value.ordinal).apply()

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, defaultVolume).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_VOLUME, value.coerceIn(0f, 1f)).apply()

    var accentEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACCENT, defaultAccent)
        set(value) = prefs.edit().putBoolean(KEY_ACCENT, value).apply()

    var practiceMinutes: Int
        get() = prefs.getInt(KEY_PRACTICE, defaultPracticeMinutes).coerceIn(0, 180)
        set(value) = prefs.edit().putInt(KEY_PRACTICE, value.coerceIn(0, 180)).apply()

    var language: Language
        get() = Language.fromCode(prefs.getString(KEY_LANGUAGE, defaultLanguage.code))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.code).apply()

    companion object {
        // 公开常量供 MainActivity.attachBaseContext 读取语言（无需实例化 Repository）
        const val PREFS_NAME = "metronome_settings"
        const val KEY_LANGUAGE = "language"
        private const val KEY_BPM = "bpm"
        private const val KEY_TS = "time_signature"
        private const val KEY_SOUND = "sound"
        private const val KEY_VOLUME = "volume"
        private const val KEY_ACCENT = "accent"
        private const val KEY_PRACTICE = "practice_minutes"
    }
}
