package com.glasskey.ime.predict

import android.content.Context
import java.io.File

/**
 * v1.6.3 短期热词表（用户需求：对历史输入的最终结果做分词保存，
 * 短期热处理——时间太长就不要了）：
 *
 *  - 每次上屏的句子（≤40 字）用当前词库做正向最大匹配（FMM）切词，
 *    切出的 ≥2 字词热度 +1，写入 filesDir/recent_hot.tsv（word\tfreq\tts）；
 *  - 读取时丢弃超过 [TTL_MS]（3 天）的条目——"热"保存，冷了自动清；
 *  - 输入法装载连续拼音词库时合并本表（热度 +50_000，介于用户词与
 *    内置词频之间），下次输入同词即更靠前。
 *
 * 写盘节流：每 [FLUSH_EVERY] 次上屏落盘一次，进程被杀最多丢几条。
 */
object RecentHotWords {

    private const val FILE = "recent_hot.tsv"
    private const val TTL_MS = 3L * 24 * 3600 * 1000      // 3 天过期
    private const val FLUSH_EVERY = 5
    private const val MAX_ENTRIES = 300
    private const val MAX_WORD_LEN = 6

    private data class Entry(val word: String, var freq: Int, var ts: Long)

    private val table = LinkedHashMap<String, Entry>()
    private var dirty = 0
    private var loaded = false

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** v1.7.4：一键清除（内存表 + 磁盘文件），供"清除全部学习数据"入口调用 */
    fun clear(ctx: Context) {
        table.clear()
        dirty = 0
        loaded = true
        try { file(ctx).delete() } catch (_: Throwable) { }
    }

    /** 上屏句 → 分词 → 热度累计（主线程调用安全：纯内存 + 节流写盘） */
    fun addFromSentence(ctx: Context, sentence: String, lexiconWords: Set<String>?) {
        if (sentence.isBlank() || sentence.length > 40) return
        ensureLoaded(ctx)
        if (lexiconWords.isNullOrEmpty()) return
        // FMM 正向最大匹配：句中出现的词库词 → 热度 +1（单字不入表）
        var i = 0
        while (i < sentence.length) {
            var matched = ""
            for (len in minOf(MAX_WORD_LEN, sentence.length - i) downTo 2) {
                val cand = sentence.substring(i, i + len)
                if (cand in lexiconWords) { matched = cand; break }
            }
            if (matched.isEmpty()) { i++; continue }
            val e = table[matched] ?: Entry(matched, 0, 0L).also { table[matched] = it }
            e.freq++
            e.ts = System.currentTimeMillis()
            i += matched.length
        }
        // 淘汰过期与超量
        val now = System.currentTimeMillis()
        table.entries.removeAll { now - it.value.ts > TTL_MS }
        if (table.size > MAX_ENTRIES) {
            table.entries.sortedByDescending { it.value.ts }
                .drop(MAX_ENTRIES).forEach { table.remove(it.key) }
        }
        if (++dirty >= FLUSH_EVERY) flush(ctx)
    }

    private fun boostFreq(freq: Int): Int = 50_000 + freq.coerceAtMost(10_000)

    fun all(): List<Pair<String, Int>> {
        val now = System.currentTimeMillis()
        return table.values.filter { now - it.ts <= TTL_MS }
            .sortedByDescending { it.freq }
            .map { it.word to boostFreq(it.freq) }
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val now = System.currentTimeMillis()
            file(ctx).takeIf { it.isFile }?.useLines { lines ->
                for (ln in lines) {
                    val t = ln.split('\t')
                    if (t.size < 3) continue
                    val ts = t[2].toLongOrNull() ?: continue
                    if (now - ts > TTL_MS) continue   // 冷词直接丢弃
                    table[t[0]] = Entry(t[0], t[1].toIntOrNull() ?: 1, ts)
                }
            }
        } catch (_: Throwable) { }
    }

    fun flush(ctx: Context) {
        dirty = 0
        try {
            file(ctx).outputStream().bufferedWriter().use { w ->
                for ((word, e) in table) w.write("$word\t${e.freq}\t${e.ts}\n")
            }
        } catch (_: Throwable) { }
    }
}
