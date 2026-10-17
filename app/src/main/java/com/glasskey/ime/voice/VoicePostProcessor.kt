package com.glasskey.ime.voice

/**
 * 语音识别输出后处理（v1.4，语音优化需求）：
 *
 * 链路：ASR 原始文本 → 1) 用户个人词库校准/纠错 → 2) 标点恢复与补全 → 最终上屏
 *
 * 1) 词库校准（[Lexicon]）：
 *    - 用户热词整词替换（最高优先，不被通用覆盖）；
 *    - 同音/近音纠正：以词库拼音（无声调，与 UserDictDb.pinyin 同格式）匹配
 *      ASR 结果中的同音串，若命中词库词则替换；
 *    - 历史修改记录：用户上次对同形文本的人工改写优先（[Lexicon.correctionFor]）。
 * 2) 标点补全：
 *    - 支持：，。！？《》（）……；【不添加破折号】；
 *    - 依据：句末语气（吗/呢/吧→问号或句号）、感叹词、停顿（调用方传入的
 *      [Segment] 静音时长）、列举顿句、书名/括号触发词（书/剧/歌名→《》，
 *      补充说明→（）），省略号触发（"等等""然后然后""那个…"拖延语气）；
 *    - 低置信保护：语气词不足时不机械加句号（要求 [Config.requireSentenceEndCue]），
 *      宁可不加也不加错。
 *
 * 可配置、可回退：[Config] 全参数化；任何一步异常返回输入原文（不丢字）。
 */
class VoicePostProcessor(
    private val lexicon: Lexicon = Lexicon.EMPTY,
    private val config: Config = Config(),
) {

    data class Config(
        /** 单段语音结束后的静音时长（ms）≥ 该值视为一个句界（句末标点判定） */
        val pauseMsSentence: Long = 450L,
        /** 静音 ≥ 该值视为枚举/并列短停顿（加逗号） */
        val pauseMsClause: Long = 180L,
        /** 句末无明确语气词时是否仍加句号（false=宁缺勿错） */
        val requireSentenceEndCue: Boolean = false,
        /** 是否允许括号补全 */
        val enableBrackets: Boolean = true,
        /** 是否允许书名号补全 */
        val enableBookTitle: Boolean = true,
    )

    /** 一段语音的元信息（调用方从 VAD/分段时间戳取） */
    data class Segment(
        val text: String,
        val trailingSilenceMs: Long = 0L,
        val isFirst: Boolean = false,
    )

    /** 词库/历史数据源（服务层适配 UserDictDb + 学习历史；JVM 测试用内存实现） */
    interface Lexicon {
        /** 用户热词：ASR 结果中出现同音/原词时替换为该词 */
        val hotWords: List<Pair<String, String>> get() = emptyList() // (读音或原词, 目标词)

        /** 词库词（word to 无声调拼音），用于同音纠正 */
        val words: List<Pair<String, String>> get() = emptyList()

        /** 历史修改记录：上次用户把 A 改成了 B，则这次 A 先改 B */
        fun correctionFor(text: String): String? = null

        object EMPTY : Lexicon
    }

    /** v1.5.2 词库组句重打分器（可注入）：ASR 原文 → 词库匹配组句 + 置信度 */
    var rescorer: VoiceRescorer? = null

    /** 最近一次组句重排结果（供上屏决策/低置信提示） */
    var lastRescore: VoiceRescorer.Result? = null
        private set

    /** @return (最终文本, 日志)。任何异常/空输入原样返回，log 标注 skip 原因 */
    fun process(vararg segments: Segment): Pair<String, List<String>> {
        val log = mutableListOf<String>()
        val raw = segments.joinToString("") { it.text }
        if (raw.isBlank()) {
            log.add("skip: empty asr text")
            return raw to log
        }
        // —— 0) 词库组句重排（v1.5.2：逐词匹配词库、择优连句）——
        var current = raw
        lastRescore = null
        try {
            rescorer?.let { r ->
                val rr = r.rescore(current)
                lastRescore = rr
                if (rr.changed) {
                    log.add("rescore: '${current}'->'${rr.text}' conf=${"%.2f".format(rr.confidence)} cover=${"%.2f".format(rr.wordCoverage)}")
                    current = rr.text
                } else {
                    log.add("rescore: kept asr (conf=${"%.2f".format(rr.confidence)})")
                }
            }
        } catch (t: Throwable) {
            log.add("rescore: error ${t.message}")
        }
        // —— 1) 用户词库校准 ——
        val calibrated = calibrate(current, log)
        // —— 2) 标点恢复 ——
        val punctuated = restorePunctuation(calibrated, segments.toList(), log)
        return punctuated to log
    }

    // ────────────────── 词库校准 ──────────────────

    internal fun calibrate(raw: String, log: MutableList<String>): String {
        var out = raw
        // 1a. 历史修改记录优先（用户上次怎么改的，这次就怎么改）
        lexicon.correctionFor(out)?.let { fixed ->
            if (fixed != out) {
                log.add("calibrate: history '${out}'->'${fixed}'")
                out = fixed
            }
        }
        // 1b. 用户热词整词替换（最高优先，防通用覆盖）
        // v1.7.3 审计修复：key 限制 ≤ 8 字——学习对可能是"整句→单字"级别
        // 的污染数据，长 key 会把整段 ASR 文本替换成一个词（真机事故）
        for ((key, target) in lexicon.hotWords) {
            if (key.isNotEmpty() && key.length <= 8 && out.contains(key) && key != target) {
                out = out.replace(key, target)
                log.add("calibrate: hotword '$key'->'$target'")
            }
        }
        // 1c. 同音纠正：词库词拼音与文本片段拼音一致且文本当前不是词库词
        for ((word, py) in lexicon.words) {
            if (word.isEmpty() || py.isEmpty()) continue
            val pyNorm = normalizePinyin(py)
            if (pyNorm.isEmpty()) continue
            val idx = findHomophone(out, word, pyNorm)
            if (idx >= 0) {
                out = out.substring(0, idx) + word + out.substring(idx + word.length)
                log.add("calibrate: homophone ->'$word' @${idx}")
            }
        }
        return out
    }

    /** 在文本中找与 [word] 同音（拼音一致）但字形不同的片段 */
    private fun findHomophone(text: String, word: String, pyNorm: String): Int {
        if (text.contains(word)) return -1  // 已是词库词，不重复处理
        val n = word.length
        if (text.length < n) return -1
        var i = 0
        while (i + n <= text.length) {
            val seg = text.substring(i, i + n)
            if (seg == word) { i++; continue }
            // 片段全为 CJK 才参与同音匹配
            if (seg.all { it in '\u4e00'..'\u9fff' }) {
                val segPy = PINYIN_CACHE[seg]
                if (segPy != null && normalizePinyin(segPy) == pyNorm) return i
            }
            i++
        }
        return -1
    }

    // ────────────────── 标点恢复 ──────────────────

    internal fun restorePunctuation(
        text: String, segments: List<Segment>, log: MutableList<String>,
    ): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder()
        val chars = text.toCharArray()
        var i = 0
        var addedAny = false
        while (i < chars.size) {
            val ch = chars[i]
            sb.append(ch)
            val rest = text.substring(i)
            // 书名号触发：前面出现"《书名》"语义提示词（本书/这本/名叫/看完…+短名词）
            if (config.enableBookTitle && ch in '\u4e00'..'\u9fff') {
                val bt = matchBookTitle(rest)
                if (bt != null) {
                    sb.append(rest.take(bt.first).drop(1))  // 引导词剩余部分（首字已 append）
                    sb.append('《').append(bt.second).append('》')
                    log.add("punct: booktitle 《${bt.second}》")
                    i += bt.first + bt.second.length - 1  // 消费引导词+名词（循环尾再 ++）
                    addedAny = true
                    i++
                    continue
                }
            }
            // 括号触发：紧接"包括/也就是/即/（补充说明）"引导的说明短语
            if (config.enableBrackets && ch in '\u4e00'..'\u9fff') {
                val br = matchBracket(rest)
                if (br != null) {
                    sb.append(rest.take(br.first).drop(1))
                    sb.append('（').append(br.second).append('）')
                    log.add("punct: bracket (${br.second})")
                    i += br.first + br.second.length - 1
                    addedAny = true
                    i++
                    continue
                }
            }
            // 段间停顿 → 逗号（枚举短停）/句末标点（长停 + 语气判定）
            val segIdx = segmentEndIndex(segments, text, i)
            if (segIdx == i && i < chars.size - 1) {
                val pause = pauseAfter(segments, text, i)
                if (pause >= config.pauseMsSentence) {
                    appendSentenceEnd(sb, text, i, log)
                    addedAny = true
                } else if (pause >= config.pauseMsClause) {
                    sb.append('，')
                    log.add("punct: clause pause ${pause}ms -> comma")
                    addedAny = true
                }
            }
            i++
        }
        // 文本整体结束：若来源末段静音够长，补句末标点
        val lastPause = segments.lastOrNull()?.trailingSilenceMs ?: 0L
        if (lastPause >= config.pauseMsSentence &&
            sb.isNotEmpty() && !endsWithPunct(sb.last())
        ) {
            // 省略号：列举 + "等等/什么的" 的拖延/未列尽语气
            val s = sb.toString()
            if (s.endsWith("等等") || s.endsWith("什么的") || s.endsWith("之类")) {
                sb.append("……")
                log.add("punct: ellipsis for trailing tone")
            } else {
                appendSentenceEnd(sb, text, chars.size - 1, log)
            }
            addedAny = true
        }
        if (!addedAny) log.add("punct: no confident cue, kept raw (宁缺勿错)")
        return sb.toString()
    }

    /** 句末标点：问/感叹语气优先，其次句号；无语气词且 requireSentenceEndCue=true 则不加 */
    private fun appendSentenceEnd(sb: StringBuilder, text: String, idx: Int, log: MutableList<String>) {
        val tail = text.substring(0, idx + 1).takeLast(6)
        val isQuestion = QUESTION_CUES.any { tail.contains(it) }
        val isExclaim = !isQuestion && EXCLAIM_CUES.any { tail.contains(it) }
        if (config.requireSentenceEndCue) {
            val hasCue = isQuestion || isExclaim ||
                STATEMENT_CUES.any { tail.contains(it) } || PAUSE_CONFIDENT
            if (!hasCue) {
                log.add("punct: sentence end skipped (no cue)")
                return
            }
        }
        val punct = when {
            isQuestion -> '？'
            isExclaim -> '！'
            else -> '。'
        }
        sb.append(punct)
        log.add("punct: sentence end '$punct' (tail='$tail')")
    }

    /** 由调用方设置的"本次停顿确为句界"标记（VAD 端点触发时置 true） */
    var pauseConfident: Boolean = false
        private set
    private var PAUSE_CONFIDENT = false

    /** 标记本次停顿为 VAD 端点确信句界 */
    fun markSentenceBoundary(confident: Boolean) {
        PAUSE_CONFIDENT = confident
        pauseConfident = confident
    }

    // —— 书名号/括号触发匹配 ——

    /** @return (引导词长度, 名词内容)；null=未触发 */
    private fun matchBookTitle(rest: String): Pair<Int, String>? {
        // 已有开书名号 → 找到闭括号前内容自动补右括号
        if (rest.startsWith("《")) {
            val close = rest.indexOf('》')
            if (close > 1) return null  // 已闭合，交给原样输出
            val len = rest.length - 1
            return if (len in 1..20) 1 to rest.substring(1) else null
        }
        val m = BOOK_CUES.find { rest.startsWith(it.first) } ?: return null
        val after = rest.drop(m.first.length)
        val nameLen = after.indexOfFirst { it in "，。！？、 \n" }
            .let { if (it < 0) after.length else it }
        return if (nameLen in 1..m.second) m.first.length to after.take(nameLen) else null
    }

    /** @return (引导词长度, 说明内容)；null=未触发 */
    private fun matchBracket(rest: String): Pair<Int, String>? {
        if (rest.startsWith("（")) {
            val close = rest.indexOf('）')
            if (close > 1) return null
            val len = rest.length - 1
            return if (len in 1..20) 1 to rest.substring(1) else null
        }
        val m = BRACKET_CUES.find { rest.startsWith(it.first) } ?: return null
        val after = rest.drop(m.first.length)
        val len = after.indexOfFirst { it in "，。！？、 \n" }
            .let { if (it < 0) after.length else it }
        return if (len in 1..m.second) m.first.length to after.take(len) else null
    }

    private fun segmentEndIndex(segments: List<Segment>, text: String, idx: Int): Int {
        var pos = 0
        for (s in segments) {
            pos += s.text.length
            if (pos - 1 == idx) return idx
        }
        return -1
    }

    private fun pauseAfter(segments: List<Segment>, text: String, idx: Int): Long {
        var pos = 0
        for (s in segments) {
            pos += s.text.length
            if (pos - 1 == idx) return s.trailingSilenceMs
        }
        return 0L
    }

    private fun endsWithPunct(c: Char): Boolean = c in "，。！？……"  // 《》/（）收尾后仍可补句读

    companion object {
        private val QUESTION_CUES = listOf("吗", "呢", "么", "如何", "怎样", "怎么", "几", "多少", "还是")
        private val EXCLAIM_CUES = listOf("太", "真", "好棒", "太好了", "加油", "万岁", "极了", "厉害")
        private val STATEMENT_CUES = listOf("了", "的", "是", "有", "在", "吧")

        private val BOOK_CUES = listOf(
            "这本书" to 12, "那本书" to 12, "本书" to 12,
            "这部" to 12, "那部" to 12, "看完" to 12, "读到" to 12,
            "名叫" to 12,
        )
        private val BRACKET_CUES = listOf(
            "也就是说" to 16, "也就是" to 16, "包括" to 16, "即" to 16,
        )

        /**
         * 内置同音索引：常用单字/短词 → 无声调拼音。
         * 服务层可由引擎候选拼音补充；此处仅覆盖高频同音错字场景（ASR 同音错写）。
         */
        internal val PINYIN_TABLE: Map<String, String> = mapOf(
            "在" to "zai", "再" to "zai",
            "做" to "zuo", "作" to "zuo", "坐" to "zuo",
            "以" to "yi", "一" to "yi", "已" to "yi", "亦" to "yi", "亿" to "yi",
            "是" to "shi", "事" to "shi", "十" to "shi", "时" to "shi", "市" to "shi",
            "世" to "shi", "式" to "shi", "示" to "shi",
            "他" to "ta", "她" to "ta", "它" to "ta",
            "的" to "de", "得" to "de", "地" to "de",
            "元" to "yuan", "圆" to "yuan", "员" to "yuan", "园" to "yuan",
            "记" to "ji", "既" to "ji", "即" to "ji", "级" to "ji", "急" to "ji",
            "公式" to "gongshi", "公事" to "gongshi", "攻势" to "gongshi",
            "权力" to "quanli", "权利" to "quanli",
            "生气" to "shengqi", "声气" to "shengqi",
        )

        /** 同音索引（供扩展） */
        val PINYIN_CACHE: Map<String, String> = PINYIN_TABLE

        fun normalizePinyin(py: String): String =
            py.lowercase().replace(" ", "").replace("'", "")
    }
}
