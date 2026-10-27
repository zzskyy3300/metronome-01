package com.example.metronome.audio

import com.example.metronome.data.SoundType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 波形合成器：用代码生成 4 种合成音色（正弦/方波/木鱼/响木）的单次击打 PCM（归一化到 [-1,1] 的 DoubleArray）。
 *
 * 男声 / 女声（[SoundType.MALE] / [SoundType.FEMALE]）不在此合成，
 * 由 [MetronomeEngine] 从 assets/voice 加载 WAV 文件实现，随语言切换中/英语音。
 *
 * 设计目标：
 * - 不引入任何外部音频文件用于合成音色，纯算法生成，包体极小。
 * - 每个击打为短促瞬态（~30-60ms）并带指数衰减包络，避免爆破音与拖尾。
 * - 返回归一化样本，振幅由 [MetronomeEngine] 在混合时按重拍/弱拍增益统一缩放。
 */
object WaveformSynthesizer {

    /** 生成指定音色的一次击打波形。MALE/FEMALE 返回空数组（由引擎加载 WAV）。 */
    fun generateClick(sound: SoundType, sampleRate: Int): DoubleArray = when (sound) {
        SoundType.SINE -> sineBurst(sampleRate, freqHz = 880.0, durationMs = 50.0, decay = 18.0)
        SoundType.SQUARE -> squareBurst(sampleRate, freqHz = 1000.0, durationMs = 40.0, decay = 28.0)
        SoundType.WOODBLOCK -> woodblock(sampleRate, freqHz = 1800.0, durationMs = 60.0, decay = 30.0)
        SoundType.CLAVE -> sineBurst(sampleRate, freqHz = 2500.0, durationMs = 40.0, decay = 32.0)
        SoundType.MALE, SoundType.FEMALE -> DoubleArray(0)
    }

    /** 正弦音：单一频率正弦 × 指数衰减包络。 */
    private fun sineBurst(sampleRate: Int, freqHz: Double, durationMs: Double, decay: Double): DoubleArray {
        val n = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val env = exp(-decay * t)
            out[i] = sin(2 * PI * freqHz * t) * env
        }
        return out
    }

    /** 方波音：方波 × 衰减包络。 */
    private fun squareBurst(sampleRate: Int, freqHz: Double, durationMs: Double, decay: Double): DoubleArray {
        val n = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val out = DoubleArray(n)
        val period = sampleRate / freqHz
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val env = exp(-decay * t)
            val sq = if (((i % period.toInt()).toInt()) < period / 2.0) 1.0 else -1.0
            out[i] = sq * env
        }
        return out
    }

    /** 木鱼：基频正弦 + 开头极短噪声脉冲，模拟敲击瞬态与共鸣。 */
    private fun woodblock(sampleRate: Int, freqHz: Double, durationMs: Double, decay: Double): DoubleArray {
        val n = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val out = DoubleArray(n)
        val noiseLen = (sampleRate * 0.004).toInt() // 4ms 噪声瞬态
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            val env = exp(-decay * t)
            val tone = sin(2 * PI * freqHz * t)
            val noise = if (i < noiseLen) (Random.nextDouble() * 2 - 1) * exp(-120.0 * t) else 0.0
            out[i] = (tone * 0.8 + noise * 0.5) * env
        }
        return out
    }
}
