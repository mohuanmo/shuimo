package com.glasskey.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音后处理：用户词库校准 + 标点恢复 单元测试 */
class VoicePostProcessorTest {

    private class Lex(
        override val hotWords: List<Pair<String, String>> = emptyList(),
        override val words: List<Pair<String, String>> = emptyList(),
        private val correction: String? = null,
    ) : VoicePostProcessor.Lexicon {
        override fun correctionFor(text: String): String? = correction
    }

    private val log = mutableListOf<String>()

    // ── 用户词库校准 ──

    @Test
    fun hotwordReplace_userDictWins() {
        val p = VoicePostProcessor(Lex(hotWords = listOf("腾讯" to "腾讯科技有限公司")))
        val (out, lg) = p.process(VoicePostProcessor.Segment("我在腾讯上班"))
        assertEquals("我在腾讯科技有限公司上班", out)
        assertTrue(lg.any { it.startsWith("calibrate: hotword") })
    }

    @Test
    fun homophoneCorrection_viaLexiconPinyin() {
        // ASR 把"权利"写成"权力"，用户词库记录 权利 quanli
        val p = VoicePostProcessor(
            Lex(
                hotWords = listOf("quanli" to "权利"),
                words = listOf("权力" to "quanli", "权利" to "quanli"),
            )
        )
        val (out, _) = p.process(VoicePostProcessor.Segment("保护我们的权力"))
        // 热词替换（读音→目标词）优先生效
        assertEquals("保护我们的权利", out)
    }

    @Test
    fun historyCorrection_appliedFirst() {
        val p = VoicePostProcessor(Lex(correction = "小明同学"))
        val (out, lg) = p.process(VoicePostProcessor.Segment("晓 明 同 学"))
        assertEquals("小明同学", out)
        assertTrue(lg.any { it.startsWith("calibrate: history") })
    }

    @Test
    fun calibration_noLexicon_noChange() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(VoicePostProcessor.Segment("今天天气不错"))
        assertEquals("今天天气不错", out)
    }

    // ── 标点恢复 ──

    @Test
    fun questionTone_getsQuestionMark() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(
            VoicePostProcessor.Segment("你吃了吗", trailingSilenceMs = 800)
        )
        assertEquals("你吃了吗？", out)
    }

    @Test
    fun exclaimTone_getsExclaimMark() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(
            VoicePostProcessor.Segment("这个方案太好了", trailingSilenceMs = 800)
        )
        assertEquals("这个方案太好了！", out)
    }

    @Test
    fun statement_getsPeriod() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(
            VoicePostProcessor.Segment("今天天气不错", trailingSilenceMs = 800)
        )
        assertEquals("今天天气不错。", out)
    }

    @Test
    fun shortPause_getsComma() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(
            VoicePostProcessor.Segment("上午", trailingSilenceMs = 250),
            VoicePostProcessor.Segment("开会", trailingSilenceMs = 800),
        )
        assertEquals("上午，开会。", out)
    }

    @Test
    fun bookTitle_bracketAdded() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(
            VoicePostProcessor.Segment("我最近在看这本书活着", trailingSilenceMs = 800)
        )
        assertEquals("我最近在看这本书《活着》。", out)
        assertFalse(out.contains("——"))  // 不加破折号
    }

    @Test
    fun bracket_addedForSupplement() {
        val p = VoicePostProcessor()
        val (out, _) = p.process(
            VoicePostProcessor.Segment("下周三也就是十月二十一号出发", trailingSilenceMs = 800)
        )
        assertTrue("应补括号: $out", out.contains("（十月二十一号出发）"))
    }

    @Test
    fun ellipsis_forTrailingTone() {
        // 省略号：拖延语气词触发（"等等"）→ 以列举收尾补 ……
        val p = VoicePostProcessor()
        val raw = "苹果香蕉橘子等等"
        val (out, _) = p.process(VoicePostProcessor.Segment(raw, trailingSilenceMs = 800))
        assertTrue("列举+等等宜补省略号: $out", out.contains("……") || out.contains("。"))
    }

    @Test
    fun neverAddsDash() {
        val p = VoicePostProcessor()
        val samples = listOf(
            "你吃了吗", "太好了", "今天天气不错", "上午", "开会", "包括北京上海广州",
        )
        for (s in samples) {
            val (out, _) = p.process(VoicePostProcessor.Segment(s, trailingSilenceMs = 800))
            assertFalse("不允许破折号: $out", out.contains("——") || out.contains("—"))
        }
    }

    @Test
    fun lowConfidence_noPunctuation_keptRaw() {
        // 短静音 + 无语气词 + 保守配置 → 不加标点（宁缺勿错）
        val p = VoicePostProcessor(config = VoicePostProcessor.Config(requireSentenceEndCue = true))
        val (out, lg) = p.process(VoicePostProcessor.Segment("随便说点什么", trailingSilenceMs = 100))
        assertFalse("无停顿线索不应强加句号: $out", out.endsWith("。"))
        assertTrue(lg.any { it.contains("skipped") || it.contains("no confident") })
    }

    @Test
    fun order_calibrateThenPunctuate() {
        // 校准后再加标点：热词替换出现在带标点的输出里
        val p = VoicePostProcessor(Lex(hotWords = listOf("鹅厂" to "腾讯")))
        val (out, _) = p.process(
            VoicePostProcessor.Segment("我在鹅厂上班", trailingSilenceMs = 800)
        )
        assertEquals("我在腾讯上班。", out)
    }

    @Test
    fun emptyInput_fallsBack() {
        val p = VoicePostProcessor()
        val (out, lg) = p.process(VoicePostProcessor.Segment(""))
        assertEquals("", out)
        assertTrue(lg.any { it.startsWith("skip") })
    }

    @Test
    fun latency_overhead_small() {
        val p = VoicePostProcessor(Lex(words = (1..200).map { "词$it" to "ci" }))
        val t0 = System.nanoTime()
        repeat(200) { p.process(VoicePostProcessor.Segment("今天下午三点的会议别忘了", trailingSilenceMs = 800)) }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / 200
        assertTrue("后处理单次应 <2ms: ${perCallMs}ms", perCallMs < 2.0)
    }
}
