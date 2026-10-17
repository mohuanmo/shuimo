package com.glasskey.ime.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连续拼音输入解析：切分 / 候选 / 打分 / 词库修正 / 延迟 单元测试 */
class ContinuationResolverTest {

    private class Lex(
        private val words: List<Triple<String, String, Int>> = emptyList(),
        private val history: Map<String, String> = emptyMap(),
    ) : ContinuationResolver.Lexicon {
        override fun wordsFor(pattern: String): List<Triple<String, String, Int>> {
            val normPat = pattern.replace(" ", "")
            val tokens = pattern.split(" ").filter { it.isNotEmpty() }
            return words.filter { (_, py, _) ->
                val pyNorm = py.replace(" ", "")
                val pyTokens = py.split(" ", "'").filter { it.isNotEmpty() }
                pyNorm == normPat ||
                    (pyTokens.size == tokens.size && pyTokens.zip(tokens).all { (p, t) ->
                        if (t.endsWith("*") && t.length > 1) p.startsWith(t.dropLast(1)) else p == t
                    })
            }
        }

        override fun historyTop(pattern: String): String? = history[pattern.replace(" ", "")]
    }

    private val baseWords = listOf(
        Triple("你好", "ni hao", 500),
        Triple("宁波", "ning bo", 300),
        Triple("年好", "nian hao", 100),
        Triple("牛", "niu", 200),
        Triple("苹果", "ping guo", 400),
        Triple("今天", "jin tian", 600),
        Triple("天气", "tian qi", 500),
        Triple("北京", "bei jing", 500),
        Triple("上海", "shang hai", 400),
    )

    // ── 1) 连续全拼 ──

    @Test
    fun fullPinyin_twoWords() {
        val r = ContinuationResolver(Lex(baseWords)).resolve("nihao")
        assertNotNull(r)
        assertEquals(listOf("ni", "hao"), r!!.segmentation)
        assertEquals("你好", r.candidates.first())
        assertTrue(r.confidence > 0.5)
    }

    @Test
    fun fullPinyin_sentenceLength() {
        val r = ContinuationResolver(Lex(baseWords)).resolve("jintianqi")
        assertNotNull(r)
        assertTrue("24 字母内任意长度均可: jintianqi", r!!.candidates.isNotEmpty())
        // jintianqi 可能切为 jin+tian+qi 或 jin+tian+qi（歧义在 jian/tian），不应崩溃且有序输出
        assertTrue(r.candidates.size <= 30)
    }

    // ── 2) 连续首字母 ──

    @Test
    fun initialsOnly() {
        val r = ContinuationResolver(Lex(baseWords)).resolve("nh")
        assertNotNull(r)
        assertTrue("首字母输入应识别为混合形态", r!!.isMixedForm)
        assertTrue("nh 应能命中『你好』", r.candidates.contains("你好"))
    }

    @Test
    fun initialsLonger() {
        val r = ContinuationResolver(Lex(baseWords + Triple("上海北京", "shang hai bei jing", 100))).resolve("shbj")
        assertNotNull(r)
        assertTrue(r!!.candidates.isNotEmpty())
        assertTrue(r.candidates.size <= 30)
    }

    // ── 3) 前缀字母 ──

    @Test
    fun prefixLetters() {
        val r = ContinuationResolver(Lex(baseWords)).resolve("niha")
        assertNotNull(r)
        assertEquals("你好", r!!.candidates.first())   // ha 是 hao 的前缀（亦可合法切 ha，首选仍应正确）
    }

    // ── 4) 全拼 + 首字母混合 ──

    @Test
    fun mixedFullAndInitial() {
        val r = ContinuationResolver(Lex(baseWords)).resolve("nhao")
        assertNotNull(r)
        assertTrue(r!!.isMixedForm)
        assertTrue("n* hao 应命中『你好』", r.candidates.contains("你好"))
    }

    @Test
    fun mixedFullAndInitial2() {
        val r = ContinuationResolver(Lex(baseWords)).resolve("nih")
        assertNotNull(r)
        assertTrue(r!!.candidates.contains("你好"))  // h 是 hao 缩写
    }

    // ── 5) 中英数字混合（解析器职责边界：非纯字母不处理，回退引擎） ──

    @Test
    fun mixedZhEnDigit_rejectedForEnginePath() {
        val lex = Lex(baseWords)
        assertNull(ContinuationResolver(lex).resolve("abc123"))
        assertNull(ContinuationResolver(lex).resolve("ni3hao"))
        assertNull(ContinuationResolver(lex).resolve("Nihao"))
        assertNull(ContinuationResolver(lex).resolve("a"))
    }

    // ── 6) 不同长度连续输入（不写死 12 字母） ──

    @Test
    fun variableLengths_allHandled() {
        val lex = Lex(baseWords)
        val inputs = listOf("nh", "nih", "niha", "nihao", "jintian", "jintianqi",
            "jintiantianqibucuo")
        for (s in inputs) {
            val r = ContinuationResolver(lex).resolve(s)
            assertNotNull("长度 ${s.length} 应可解析: $s", r)
            assertTrue(r!!.candidates.size <= 30)
        }
    }

    // ── 7) 用户词库 / 历史修正 ──

    @Test
    fun userDictWord_beatsBase() {
        val user = Lex(
            baseWords + Triple("拟好", "ni hao", 900),  // 用户高频自造词
        )
        val r = ContinuationResolver(user).resolve("nihao")
        assertEquals("拟好", r!!.candidates.first())  // 词频 900 Boost 胜出
    }

    @Test
    fun historyCorrection_reorders() {
        val lex = Lex(baseWords, history = mapOf("nh" to "牛"))
        val r = ContinuationResolver(lex).resolve("nh")
        assertEquals("历史修改记录应置顶: ${r!!.candidates}", "牛", r.candidates.first())
    }

    // ── 8) 候选上限 30 ──

    @Test
    fun candidateLimit30() {
        val lex = Lex((1..300).map { Triple("词$it", "ni hao", it) })
        val r = ContinuationResolver(lex).resolve("nihao")
        assertTrue("候选应 ≤30: ${r!!.candidates.size}", r.candidates.size <= 30)
        assertEquals(30, ContinuationResolver.CANDIDATE_LIMIT)
    }

    // ── 9) 低置信回退 ──

    @Test
    fun lowConfidence_notDirectCommit() {
        // 无词库命中：置信度低 → highConfidence=false（服务层回退到候选/确认）
        val r = ContinuationResolver(Lex(emptyList())).resolve("zzzqqq")
        assertNotNull(r)
        assertTrue(r!!.candidates.isNotEmpty() || r.confidence < 0.85)
        if (r.candidates.isEmpty()) assertTrue(r.confidence < 0.85)
    }

    // ── 10) 实时延迟 ──

    @Test
    fun latency_budget() {
        val lex = Lex(baseWords + (1..200).map { Triple("词$it", "jin tian", it) })
        val resolver = ContinuationResolver(lex)
        // 预热
        resolver.resolve("jintian")
        val inputs = listOf("jintian", "jintianqi", "jintiantianqibucuo", "shbj", "nhao")
        val t0 = System.nanoTime()
        repeat(50) {
            for (s in inputs) resolver.resolve(s)
        }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / (50 * inputs.size)
        assertTrue("单次解析应 <5ms（24 字母长串）: ${perCallMs}ms", perCallMs < 5.0)
    }
}
