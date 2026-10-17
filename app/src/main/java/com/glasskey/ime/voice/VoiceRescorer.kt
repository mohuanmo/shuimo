package com.glasskey.ime.voice

import android.content.res.AssetManager
import android.util.Log

/**
 * 语音识别词库组句重打分器（v1.5.2）：
 *
 * ASR（sherpa-onnx 流式 Zipformer）输出的是逐字转写，同音错字常见
 * （在/再、做/作、公事/公式…）。本器把 ASR 文本放入"字→读音"格点：
 *  - 每个位置可选：保留 ASR 原字（baseline）或替换为词库中拼音一致的高频词；
 *  - 维特比选取得分最高的覆盖路径（词覆盖得分 = 长度奖励 + 词频奖励），
 *    优先连成"能在词库中连成句子"的结果；
 *  - 同时计算整句置信度（平均每字得分，1.0 = 全程单字 baseline），
 *    供上屏决策：高置信直接上屏，低置信提示用户核对。
 *
 * 数据：assets/dict/char_pinyin.tsv（字→读音，多音字多行读音）+
 *       assets/dict/voice_lexicon.tsv（word\tpinyin\tfreq，高频词 6 万）。
 * 后台线程懒加载；加载完成前 rescore 原样返回（不阻塞语音链路）。
 */
class VoiceRescorer(private val assets: AssetManager) {

    data class Result(
        val text: String,
        val confidence: Double,   // 平均每字得分（≥1.0 说明有词库词命中）
        val changed: Boolean,
        val wordCoverage: Double, // 非单字（被词库词覆盖）字符占比
    )

    // 拼音（无声调、无分隔）→ 候选词列表（按词频降序）
    private var pinyinIndex: Map<String, List<Pair<String, Int>>> = emptyMap()
    // v1.5.5：模糊音归一索引（z/zh、c/ch、s/sh、n/l、f/h、an/ang、en/eng、in/ing）
    private var pinyinFuzzyIndex: Map<String, List<Pair<String, Int>>> = emptyMap()
    // 字 → 读音集合
    private var charReadings: Map<Char, List<String>> = emptyMap()
    @Volatile private var loaded = false
    private val lock = Any()

    /** 后台预加载（语音面板预热时调用） */
    fun warmUp() {
        if (loaded) return
        Thread {
            try { load() } catch (t: Throwable) {
                Log.w("GkVoiceRescore", "lexicon load failed", t)
            }
        }.start()
    }

    val ready: Boolean get() = loaded

    private fun load() {
        synchronized(lock) {
            if (loaded) return
            val cr = HashMap<Char, List<String>>()
            assets.open("dict/char_pinyin.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (ln in lines) {
                    val i = ln.indexOf('\t')
                    if (i != 1) continue
                    val rs = ln.substring(i + 1).split(' ').filter { it.isNotBlank() }
                    if (rs.isNotEmpty()) cr[ln[0]] = rs
                }
            }
            val idx = HashMap<String, MutableList<Pair<String, Int>>>()
            assets.open("dict/voice_lexicon.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (ln in lines) {
                    val p = ln.split('\t')
                    if (p.size < 3) continue
                    val word = p[0]; val py = p[1]; val fr = p[2].toIntOrNull() ?: continue
                    if (word.isEmpty() || py.isEmpty()) continue
                    idx.getOrPut(py) { mutableListOf() }.add(word to fr)
                }
            }
            for (v in idx.values) v.sortByDescending { it.second }
            charReadings = cr
            pinyinIndex = idx
            loaded = true
            Log.i("GkVoiceRescore", "lexicon ready: chars=${cr.size} pinyinKeys=${idx.size}")
        }
    }

    /** ASR 文本 → 词库组句重排。任何异常/未就绪原样返回（不丢字）。 */
    fun rescore(text: String): Result {
        if (!loaded || text.isEmpty()) return Result(text, 1.0, false, 0.0)
        return try {
            val n = text.length
            // dp[i] = (best score, back-pointer (prevPos, word))
            val score = DoubleArray(n + 1)
            val backW = arrayOfNulls<String>(n + 1)
            val backP = IntArray(n + 1) { -1 }
            for (j in 1..n) {
                // 默认转移：单字 baseline（保留 ASR 原字）
                var best = score[j - 1] + 1.0
                var bW = ""
                var bP = j - 1
                // 词转移：[i, j) 拼音组合命中词库（j-i ≤ 8）
                val maxLen = minOf(8, j)
                for (len in 2..maxLen) {
                    val i = j - len
                    val seg = text.substring(i, j)
                    if (!seg.all { it in '\u4e00'..'\u9fff' }) continue
                    val py = matchPinyin(seg) ?: continue
                    // v1.5.5：先精确匹配，再模糊音归一匹配（近音词得分略降）
                    var cands = pinyinIndex[py]
                    var fuzzyHit = false
                    if (cands.isNullOrEmpty()) {
                        cands = pinyinFuzzyIndex[fuzzyNorm(py)]
                        fuzzyHit = cands != null
                    }
                    if (cands.isNullOrEmpty()) continue
                    val (word, freq) = cands[0]
                    // 打分：长度奖励 + 词频奖励；命中词与原串相同则无增益（走单字路径等价）
                    val freqBonus = kotlin.math.ln((freq.coerceIn(1, 30000) + 1.0)) / kotlin.math.ln(30001.0)
                    val s = score[i] + 1.0 + len * 0.35 + freqBonus * 0.8 - (if (fuzzyHit) 0.25 else 0.0)
                    if (s > best) { best = s; bW = word; bP = i }
                }
                score[j] = best
                backW[j] = bW
                backP[j] = bP
            }
            // 回溯
            val parts = ArrayList<String>()
            var j = n
            var covered = 0
            while (j > 0) {
                val w = backW[j]
                if (w.isNullOrEmpty()) { parts.add(text[j - 1].toString()); j-- }
                else { parts.add(w); covered += w.length; j = backP[j] }
            }
            parts.reverse()
            val out = parts.joinToString("")
            val confidence = score[n] / n
            val coverage = covered.toDouble() / n
            Result(out, confidence, out != text, coverage)
        } catch (t: Throwable) {
            Log.w("GkVoiceRescore", "rescore failed", t)
            Result(text, 1.0, false, 0.0)
        }
    }

    /** v1.5.5：模糊音归一（近音合并），用于近似拼音匹配。
     *  规则：zh/ch/sh→z/c/s；ang/eng/ing→an/en/in；n 与 l 归一为 n；f 归一为 h。 */
    private fun fuzzyNorm(py: String): String {
        var s = py.replace("ang", "an").replace("eng", "en").replace("ing", "in")
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val two = if (i + 2 <= s.length) s.substring(i, i + 2) else ""
            when (two) {
                "zh" -> { sb.append('z'); i += 2 }
                "ch" -> { sb.append('c'); i += 2 }
                "sh" -> { sb.append('s'); i += 2 }
                else -> { sb.append(s[i]); i++ }
            }
        }
        s = sb.toString().replace('l', 'n').replace('f', 'h')
        return s
    }

    /** 计算片段拼音串（无声调无分隔）；多读音枚举组合，任一命中词库键即返回。
     *  未收录字 → 该组合失败（宁可不组词，不猜读音）。 */
    private fun matchPinyin(seg: String): String? {
        val options = ArrayList<List<String>>(seg.length)
        for (ch in seg) {
            val rs = charReadings[ch] ?: return null
            options.add(rs)
        }
        if (options.any { it.size > 4 }) return null  // 异常数据保护
        // 组合枚举（span ≤ 8，读音 ≤ 4，最坏 65536 — 上限保护：总组合 > 2048 放弃）
        var total = 1
        for (o in options) total *= o.size
        if (total > 2048) return null
        val sb = StringBuilder()
        fun rec(i: Int): String? {
            if (i == options.size) return pinyinIndex.containsKey(sb.toString()).takeIf { it }?.let { sb.toString() }
            for (py in options[i]) {
                val mark = sb.length
                sb.append(py)
                rec(i + 1)?.let { return it }
                sb.setLength(mark)
            }
            return null
        }
        return rec(0)
    }
}
