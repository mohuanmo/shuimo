package com.glasskey.ime.t9

import android.content.Context
import java.io.File

/**
 * v1.5.7/1.5.8 九键简拼（首字母）索引：
 *
 * 问题：T9 一键多字母，用户按"一句话每个字的拼音首字母"对应的数字串时
 * （如"你好"→ n h → 6 4），引擎侧 T9 全拼解码（t9Pinyin 输出完整拼音串）
 * 无法覆盖首字母形态 → 九键候选恒空。
 *
 * 方案：纯 Kotlin 侧索引——
 *   char_pinyin.tsv（字→读音，11612 字）+ voice_lexicon.tsv（word\tpinyin\tfreq，6 万高频词）
 *   → 对每个词，逐字查读音取首字母 → 映射 T9 数字（2=abc…9=wxyz）
 *   → 数字串 → 词列表（按词频降序）。
 *
 * v1.5.8 升级：
 *  - 前缀匹配：输入数字串是词数字串的前缀即命中（多字母识别），继续按键缩小
 *    范围消歧；exact 命中永远排在 prefix 命中之前；
 *  - 模糊拼音：z/zh、c/ch、s/sh 在 T9 数字层天然同键；l/n（5/6）、f/h（3/4）
 *    数字不同 → 查询时生成数字变体（每维可互换，组合上限 32）覆盖模糊音；
 *  - 多音字：每字取全部读音首字母（组合上限 8，主读音优先）；
 *  - 纯汉字、长度 1..6 的词参与索引；后台线程构建，主线程只读。
 */
object T9InitialIndex {

    private const val MAX_COMBOS = 8      // 多音字首字母组合上限/词
    private const val PER_KEY_LIMIT = 24  // 每个数字串存储上限
    private const val MAX_FUZZY_VARIANTS = 32

    /** 数字串 → (词, 词频)，构建后不可变 */
    @Volatile
    private var index: Map<String, List<Pair<String, Int>>> = emptyMap()

    /** 排序键（前缀二分用），与 index 同步赋值 */
    @Volatile
    private var sortedKeys: List<String> = emptyList()

    @Volatile
    private var building = false

    val ready: Boolean get() = index.isNotEmpty()

    /** 后台构建（幂等；重复调用直接忽略） */
    fun buildAsync(ctx: Context) {
        if (building || ready) return
        synchronized(this) {
            if (building || ready) return
            building = true
        }
        Thread {
            try {
                deployFromAssets(ctx, "dict/char_pinyin.tsv")
                deployFromAssets(ctx, "dict/voice_lexicon.tsv")
                build(File(ctx.filesDir, "dict"))
            } catch (_: Throwable) { }
            building = false
        }.apply { name = "gk-t9-index"; isDaemon = true; start() }
    }

    /** assets → filesDir（存在且非空则跳过） */
    private fun deployFromAssets(ctx: Context, assetPath: String) {
        val out = File(ctx.filesDir, assetPath)
        if (out.isFile && out.length() > 0) return
        out.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
    }

    /** 纯 JVM 构建（可测）：dictDir 下需有 char_pinyin.tsv / voice_lexicon.tsv（已部署） */
    fun build(dictDir: File) {
        val charInitials = HashMap<Char, LinkedHashSet<Char>>(16384)
        File(dictDir, "char_pinyin.tsv").takeIf { it.isFile }?.useLines { lines ->
            for (ln in lines) {
                val t = ln.split('\t')
                if (t.size < 2) continue
                val ch = t[0].firstOrNull() ?: continue
                val py = t[1].trim()
                val ini = py.firstOrNull() ?: continue
                if (ini in 'a'..'z') charInitials.getOrPut(ch) { LinkedHashSet() }.add(ini)
            }
        } ?: return

        val acc = HashMap<String, MutableList<Pair<String, Int>>>(1 shl 16)
        File(dictDir, "voice_lexicon.tsv").takeIf { it.isFile }?.useLines { lines ->
            for (ln in lines) {
                val t = ln.split('\t')
                if (t.size < 2) continue
                val word = t[0].trim()
                val freq = t.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
                if (word.isEmpty() || word.length > 6) continue
                val digitsList = toDigits(word, charInitials) ?: continue
                for (digits in digitsList) {
                    acc.getOrPut(digits) { ArrayList(4) }.add(word to freq)
                }
            }
        } ?: return

        val idx = acc.mapValues { (_, list) ->
            list.sortedByDescending { it.second }.take(PER_KEY_LIMIT)
        }
        index = idx
        sortedKeys = idx.keys.sorted()
    }

    /** 词 → T9 数字串列表；含非索引字返回 null。多音字全部组合都入索引
     *  （v1.7 修复：原只取第一种读音组合，多音字词大量漏索引 → 简拼打不出） */
    private fun toDigits(word: String, charInitials: Map<Char, LinkedHashSet<Char>>): List<String>? {
        var combos = listOf("")
        for (ch in word) {
            val inis = charInitials[ch] ?: return null
            combos = combos.flatMap { pre -> inis.map { pre + it } }.distinct().take(MAX_COMBOS)
            if (combos.isEmpty()) return null
        }
        return combos.map { digitEncode(it) }.distinct()
    }

    /** 字母 → T9 数字（2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz） */
    fun digitEncode(letters: String): String {
        val sb = StringBuilder(letters.length)
        for (c in letters) {
            sb.append(when (c) {
                in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
                in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
                in 't'..'v' -> '8'; in 'w'..'z' -> '9'
                else -> c
            })
        }
        return sb.toString()
    }

    /**
     * 查询：数字串（如 "64"）→ 词候选。
     * v1.5.8：exact 命中优先；无 exact 时取所有以 digits 为前缀的词
     * （多字母识别/消歧：继续按键缩小范围）；合并后按词频降序。
     */
    fun candidates(digits: String, limit: Int = 10): List<String> {
        if (digits.isEmpty() || !digits.all { it in '2'..'9' }) return emptyList()
        val out = LinkedHashMap<String, Int>()
        // 模糊数字变体（l/n 5↔6、f/h 3↔4）：覆盖首字母模糊音
        for (v in fuzzyVariants(digits)) {
            index[v]?.forEach { (w, f) -> out.merge(w, f, ::maxOf) }
        }
        if (out.isEmpty()) {
            // 前缀匹配（对每个模糊变体二分范围）
            for (v in fuzzyVariants(digits)) {
                val lo = lowerBound(sortedKeys, v)
                for (i in lo until sortedKeys.size) {
                    val k = sortedKeys[i]
                    if (!k.startsWith(v)) break
                    if (k.length > v.length) {
                        index[k]?.forEach { (w, f) -> out.merge(w, f, ::maxOf) }
                        if (out.size > limit * 4) break
                    }
                }
                if (out.size > limit * 4) break
            }
        }
        return out.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** 模糊数字变体：5↔6（l/n）、3↔4（f/h）逐位展开，组合上限 32 */
    internal fun fuzzyVariants(digits: String): List<String> {
        var cur = listOf(digits)
        for (i in digits.indices) {
            val alt = when (digits[i]) {
                '5' -> '6'; '6' -> '5'; '3' -> '4'; '4' -> '3'
                else -> null
            } ?: continue
            val next = ArrayList<String>(cur.size * 2)
            for (s in cur) {
                next.add(s)
                if (next.size < MAX_FUZZY_VARIANTS) {
                    next.add(s.substring(0, i) + alt + s.substring(i + 1))
                }
            }
            cur = next
            if (cur.size >= MAX_FUZZY_VARIANTS) break
        }
        return cur
    }

    private fun lowerBound(keys: List<String>, prefix: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
