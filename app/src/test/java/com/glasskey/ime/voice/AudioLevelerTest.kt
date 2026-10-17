package com.glasskey.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** 声音校平（AGC/高通/噪声门/限幅）单元测试 */
class AudioLevelerTest {

    private fun frame(rmsTarget: Float, n: Int = 320): ShortArray {
        // 正弦波，峰值 = rmsTarget * sqrt(2)
        val peak = rmsTarget * sqrt(2f)
        return ShortArray(n) { i -> (peak * Math.sin(2 * Math.PI * 440 * i / 16000.0)).toInt().toShort() }
    }

    private fun rms(pcm: ShortArray): Float {
        var s = 0.0
        for (v in pcm) s += v.toDouble() * v
        return sqrt(s / pcm.size).toFloat()
    }

    @Test
    fun quietInput_isBoosted() {
        val lv = AudioLeveler()
        repeat(40) { lv.process(frame(300f), 320) }   // 每帧新信号（模拟真实流）
        val out = frame(300f)
        lv.process(out, out.size)
        assertTrue("小声应被放大: rms=${rms(out)}", rms(out) > 1200f)
    }

    @Test
    fun loudInput_isAttenuated() {
        val lv = AudioLeveler()
        repeat(40) { lv.process(frame(25000f), 320) }
        val out = frame(25000f)
        lv.process(out, out.size)
        assertTrue("大声应被压低: rms=${rms(out)}", rms(out) < 12000f)
    }

    @Test
    fun gainBounded_neverExceedMaxGain() {
        val lv = AudioLeveler()
        repeat(100) { lv.process(frame(10f), 320) }  // 近乎无声（噪声门内）
        val out = frame(10f)
        lv.process(out, out.size)
        assertTrue("噪声门应冻结增益: rms=${rms(out)}", rms(out) < 300f)
    }

    @Test
    fun dcOffset_removed() {
        val lv = AudioLeveler()
        repeat(30) {
            val f = frame(3000f)
            for (i in f.indices) f[i] = (f[i] + 4000).toInt().coerceAtMost(32767).toShort()
            lv.process(f, f.size)
            if (it > 10) {
                val mean = f.map { v -> v.toFloat() }.average().toFloat()
                assertTrue("直流应被去除: mean=$mean", abs(mean) < 1500f)
            }
        }
    }

    @Test
    fun outputNeverClips() {
        val lv = AudioLeveler()
        repeat(60) {
            val f = frame(20000f)
            lv.process(f, f.size)
            for (v in f) assertTrue(abs(v.toInt()) <= 32767)
        }
    }

    @Test
    fun perFrameLatency_budget() {
        val lv = AudioLeveler()
        val f = frame(3000f)
        lv.process(f, f.size)  // 预热
        val t0 = System.nanoTime()
        repeat(1000) { lv.process(f, f.size) }
        val perFrameMs = (System.nanoTime() - t0) / 1e6 / 1000
        assertTrue("单帧(20ms音频)处理应 <0.5ms: ${perFrameMs}ms", perFrameMs < 0.5)
    }
}
