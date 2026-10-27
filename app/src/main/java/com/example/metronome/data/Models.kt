package com.example.metronome.data

import com.example.metronome.R

/**
 * 拍号枚举。
 *
 * 编码方式：[beatCount] 为一小节内的"拍"数（重拍/弱拍计数单位），
 * [subdivisionsPerBeat] 为每拍包含的基本细分单元数（用于精确计算采样数与弱拍细分）。
 *
 * - 简单拍号（2/4、3/4、4/4）：拍单位=四分音符，subdivisions=1。
 * - 复合拍号（6/8、12/8）：拍单位=附点四分音符=3 个八分音符，subdivisions=3。
 *   其中 6/8 视为 2 拍，12/8 视为 4 拍。
 *
 * [display] 为通用记谱符号（不随语言变化）。
 */
enum class TimeSignature(
    val display: String,
    val beatCount: Int,
    val subdivisionsPerBeat: Int,
) {
    TIME_2_4("2/4", beatCount = 2, subdivisionsPerBeat = 1),
    TIME_3_4("3/4", beatCount = 3, subdivisionsPerBeat = 1),
    TIME_4_4("4/4", beatCount = 4, subdivisionsPerBeat = 1),
    TIME_6_8("6/8", beatCount = 2, subdivisionsPerBeat = 3),
    TIME_12_8("12/8", beatCount = 4, subdivisionsPerBeat = 3);

    /** 是否为复合拍号。 */
    val isCompound: Boolean get() = subdivisionsPerBeat > 1
}

/**
 * 内置音色。共 6 种。
 * - SINE / SQUARE / WOODBLOCK / CLAVE：由 [com.example.metronome.audio.WaveformSynthesizer] 代码生成 PCM。
 * - MALE / FEMALE：人声节拍（男声/女声），由 [com.example.metronome.audio.MetronomeEngine] 从 assets 加载 WAV 文件，
 *   随 UI 语言切换中文/英文语音（中文读"一二三四"，英文读"one two three four"）。
 *
 * [stringRes] 为本地化显示名称资源。
 */
enum class SoundType(val stringRes: Int) {
    MALE(R.string.sound_male),
    FEMALE(R.string.sound_female),
    CLAVE(R.string.sound_clave),
    SINE(R.string.sound_sine),
    SQUARE(R.string.sound_square),
    WOODBLOCK(R.string.sound_woodblock),
}

/**
 * 应用语言（中/英）。仅用于 SharedPreferences 持久化与 attachBaseContext 覆盖 Locale。
 */
enum class Language(val code: String) {
    ZH("zh"),
    EN("en");

    companion object {
        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code == code } ?: ZH
    }
}
