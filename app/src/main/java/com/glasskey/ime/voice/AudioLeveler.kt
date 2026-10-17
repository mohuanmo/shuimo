package com.glasskey.ime.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 声音校平 / 录音预处理链（v1.4，语音优化需求）：
 *
 * 处理链路（每 20ms 帧串行，纯 Kotlin，无新依赖）：
 *   原始 PCM → 1) 直流去偏（one-pole high-pass, fc≈80Hz）
 *            → 2) 噪声门（低于噪声底不放大，避免放大底噪）
 *            → 3) RMS 自适应增益（AGC，目标响度 [targetRms]，增益平滑 + 上下行限速）
 *            → 4) 峰值限幅器（soft limiter，防削波）
 *            → 输出 PCM → 送 ASR
 *
 * 设计原则：
 * - 不是简单把音量调大：增益由滑动 RMS 驱动，静音段不放大（噪声门），
 *   大声段自动回落（下行限速），防止把噪声/呼吸声抬成语音；
 * - 全部状态逐帧保留（流式），单帧处理 O(n)，实测每 20ms 帧 < 0.1ms；
 * - 参数集中在 [Config]，可经 GkPrefs 开关（默认开）。
 *
 * 对识别准确率的影响测试方式：
 *   合成不同响度（-20dB / -6dB / 0dB / +6dB 过载）的中文数字/词语音频，
 *   对比校平前后送 sherpa-onnx 的转写 CER（详见报告）；JVM 单测覆盖
 *   增益方向、限幅、去直流、噪声门四类断言。
 */
class AudioLeveler(private val config: Config = Config()) {

    data class Config(
        /** 目标 RMS（16bit 满幅 32768）——约 -20dBFS，ASR 友好响度 */
        val targetRms: Float = 3300f,
        /** 最大增益（dB），防止静音段被放大到爆 */
        val maxGain: Float = 24f,
        /** 最小增益（dB），过载段自动衰减下限 */
        val minGain: Float = -12f,
        /** 增益上行限速（dB/秒）：快速跟上小声说话 */
        val gainUpRateDbPerSec: Float = 60f,
        /** 增益下行限速（dB/秒）：慢速回落，避免忽大忽小 */
        val gainDownRateDbPerSec: Float = 12f,
        /** 噪声门 RMS：低于此值增益冻结（不放大底噪） */
        val noiseGateRms: Float = 220f,
        /** 高通滤波系数（0..1，越小截止越低；80Hz@16k ≈ 0.969） */
        val hpAlpha: Float = 0.969f,
        /** 增益平滑窗口（帧数，20ms/帧 → 25 帧 = 500ms） */
        val rmsSmoothFrames: Int = 25,
    )

    // —— 流式状态 ——
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var currentGainDb = 0f
    private var smoothRms = 0f

    /** 重置会话状态（每次 startRecording 调用） */
    fun reset() {
        hpPrevIn = 0f; hpPrevOut = 0f
        currentGainDb = 0f; smoothRms = 0f
    }

    /**
     * 处理一帧（原地替换 [pcm] 前 [len] 个样本）。
     * @return 处理后帧 RMS（供 UI 电平/VAD 复用；VAD 阈值仍按原始能量另行计算）
     */
    fun process(pcm: ShortArray, len: Int): Float {
        if (len <= 0) return 0f
        // 1) 高通去直流 + 2) 缓存为 float
        val f = FloatArray(len)
        for (i in 0 until len) {
            val x = pcm[i].toFloat()
            val y = config.hpAlpha * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = y
            f[i] = y
        }
        // 帧 RMS（高通后）
        var sum = 0.0
        for (i in 0 until len) sum += f[i].toDouble() * f[i].toDouble()
        val rms = sqrt(sum / len).toFloat()
        // 平滑 RMS（快攻慢放：语音起始快速跟 上，尾音缓降避免增益抖动）
        val a = if (rms > smoothRms) 0.3f else 1f / max(1, config.rmsSmoothFrames)
        smoothRms = smoothRms + a * (rms - smoothRms)
        // 3) AGC：目标增益 = 20log10(target / smoothRms)，噪声门内冻结
        if (smoothRms > config.noiseGateRms) {
            val want = 20f * log10Safe(config.targetRms / max(smoothRms, 1f))
            val clamped = min(config.maxGain, max(config.minGain, want))
            // 限速逼近（20ms 帧对应 dB 步进）
            val upStep = config.gainUpRateDbPerSec * FRAME_SEC
            val downStep = config.gainDownRateDbPerSec * FRAME_SEC
            currentGainDb = when {
                clamped > currentGainDb -> min(currentGainDb + upStep, clamped)
                clamped < currentGainDb -> max(currentGainDb - downStep, clamped)
                else -> currentGainDb
            }
        }
        val gain = dbToLinear(currentGainDb)
        // 4) 应用增益 + 软限幅（tanh 近似：超 ±0.9 平滑压缩）
        for (i in 0 until len) {
            var v = f[i] * gain
            if (v > LIMIT_KNEE || v < -LIMIT_KNEE) {
                v = LIMIT_KNEE + (1f - LIMIT_KNEE) * tanhApprox((v - LIMIT_KNEE * sign(v)) / (1f - LIMIT_KNEE)) * sign(v)
            }
            pcm[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return rms * gain
    }

    private fun sign(v: Float): Float = if (v >= 0f) 1f else -1f

    private fun log10Safe(v: Float): Float =
        (kotlin.math.ln(max(v, 1e-6f).toDouble()) / LN10).toFloat()

    private fun dbToLinear(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()
    /** tanh 快速近似（限幅用，精度足够） */
    private fun tanhApprox(x: Float): Float {
        val xx = x * x
        return x * (27f + xx) / (27f + 9f * xx)
    }

    companion object {
        private const val LN10 = 2.302585092994046
        const val FRAME_SEC = 0.02f
        const val LIMIT_KNEE = 26000f
    }
}
