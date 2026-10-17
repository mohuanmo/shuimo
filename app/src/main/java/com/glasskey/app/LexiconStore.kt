package com.glasskey.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.6.0 词库/词频数据仓库（订阅导入中枢）：
 *
 * 管两类数据文件（均在 filesDir 下，可被用户导入替换/扩充）：
 *  - 组句词库 lexicon：`dict/voice_lexicon.tsv`（内置部署）+ `dict/imported_lexicon.tsv`（导入）
 *    格式：词<TAB>拼音（可无空格，引擎贪心切分）[TAB]词频
 *  - 语料词对 bigram：`corpus/bigram.tsv`（内置 assets 首次部署）+ `corpus/bigram_imported.tsv`（导入）
 *    格式：词1 词2 次数（空格分隔）
 *
 * 导入来源：本地文件（SAF uri）或网络链接（HTTP/HTTPS GET）。
 * [revision] 每次导入 +1，输入法侧比对后热重载词库。
 */
object LexiconStore {

    enum class Kind { LEXICON, BIGRAM }

    /** 导入版本号：IME 侧据此判断需要重载词库 */
    @Volatile
    var revision: Int = 0
        private set

    fun lexiconFile(ctx: Context) = File(ctx.filesDir, "dict/voice_lexicon.tsv")
    fun importedLexiconFile(ctx: Context) = File(ctx.filesDir, "dict/imported_lexicon.tsv")
    fun bigramFile(ctx: Context) = File(ctx.filesDir, "corpus/bigram.tsv")
    fun importedBigramFile(ctx: Context) = File(ctx.filesDir, "corpus/bigram_imported.tsv")

    /** 内置 assets 首次部署（bigram：assets/corpus/bigram.tsv → filesDir/corpus/） */
    fun deployBuiltins(ctx: Context) {
        try {
            val dst = bigramFile(ctx)
            if (!dst.isFile) {
                dst.parentFile?.mkdirs()
                ctx.assets.open("corpus/bigram.tsv").use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
            }
        } catch (_: Throwable) { /* assets 缺失则只有导入文件可用 */ }
    }

    /** 词条总数（内置 + 导入 + 订阅） */
    fun lexiconCount(ctx: Context): Int =
        countLines(lexiconFile(ctx)) + countLines(importedLexiconFile(ctx)) +
            subscriptionFiles(ctx, Kind.LEXICON).sumOf { countLines(it) }

    /** 词对总数（内置 + 导入 + 订阅） */
    fun bigramCount(ctx: Context): Int =
        countLines(bigramFile(ctx)) + countLines(importedBigramFile(ctx)) +
            subscriptionFiles(ctx, Kind.BIGRAM).sumOf { countLines(it) }

    fun countLines(f: File): Int = try {
        f.useLines { lines -> lines.count() }
    } catch (_: Throwable) { 0 }

    fun fileSizeText(f: File): String = try {
        val kb = f.length() / 1024
        if (kb >= 1024) "${"%.1f".format(kb / 1024f)}MB" else "${kb}KB"
    } catch (_: Throwable) { "-" }

    /**
     * 预览内容。
     * @param full true=完整内容；false=前 [previewLines] 行
     */
    fun content(ctx: Context, kind: Kind, full: Boolean, previewLines: Int = 300): String {
        val files = when (kind) {
            Kind.LEXICON -> listOf(lexiconFile(ctx), importedLexiconFile(ctx)) +
                subscriptionFiles(ctx, Kind.LEXICON)
            Kind.BIGRAM -> listOf(bigramFile(ctx), importedBigramFile(ctx)) +
                subscriptionFiles(ctx, Kind.BIGRAM)
        }
        val sb = StringBuilder()
        var counted = 0
        for (f in files) {
            if (!f.isFile) continue
            try {
                f.useLines { lines ->
                    for (ln in lines) {
                        if (!full && counted >= previewLines) return sb.toString()
                        sb.append(ln).append('\n')
                        counted++
                    }
                }
            } catch (_: Throwable) { }
        }
        return sb.toString()
    }

    /** 本地文件导入（SAF uri），后台线程调用。返回导入条数。 */
    fun importFromUri(ctx: Context, kind: Kind, uri: Uri): Int {
        val dst = when (kind) {
            Kind.LEXICON -> importedLexiconFile(ctx)
            Kind.BIGRAM -> importedBigramFile(ctx)
        }
        dst.parentFile?.mkdirs()
        var n = 0
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out ->
                input.copyTo(out)
            }
        } ?: throw IllegalStateException("无法读取所选文件")
        n = countLines(dst)
        onImported()
        return n
    }

    /** 网络链接导入（HTTP/HTTPS GET，跟随重定向），后台线程调用。返回导入条数。 */
    fun importFromUrl(ctx: Context, kind: Kind, urlStr: String): Int {
        val dst = when (kind) {
            Kind.LEXICON -> importedLexiconFile(ctx)
            Kind.BIGRAM -> importedBigramFile(ctx)
        }
        dst.parentFile?.mkdirs()
        downloadTo(urlStr, dst)
        val n = countLines(dst)
        onImported()
        return n
    }

    /** HTTP/HTTPS GET 下载到 [dst]，手动跟随最多 5 次重定向（订阅/导入共用） */
    private fun downloadTo(urlStr: String, dst: File) {
        var conn: HttpURLConnection? = null
        try {
            var url = URL(urlStr)
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向缺少 Location")
                    if (++redirects > 5) throw IllegalStateException("重定向次数过多")
                    url = URL(url, loc)
                    conn.disconnect()
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                conn.inputStream.use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
                break
            }
        } finally {
            conn?.disconnect()
        }
    }

    /** 清除导入数据（恢复内置） */
    fun clearImported(ctx: Context, kind: Kind) {
        when (kind) {
            Kind.LEXICON -> importedLexiconFile(ctx).delete()
            Kind.BIGRAM -> importedBigramFile(ctx).delete()
        }
        onImported()
    }

    private fun onImported() {
        revision++
    }

    // ── v1.6.8 订阅列表（链接式管理）──────────────────────────
    //
    // 每个订阅 = { 类型, 名称, URL, 上次更新时间, 上次条数 }，持久化在
    // subs/subs.json；内容文件 subs/<id>.data。加载顺序：内置 → 旧版单
    // 文件导入 → 订阅（按添加顺序，后写覆盖同词/同词对）。

    data class Subscription(
        val id: String,
        val kind: Kind,
        val name: String,
        val url: String,
        val lastUpdatedMs: Long,   // 0 = 尚未成功更新过
        val lastCount: Int,        // -1 = 尚未成功更新过
    )

    private fun subsJsonFile(ctx: Context) = File(ctx.filesDir, "subs/subs.json")

    private fun subDataFile(ctx: Context, id: String) =
        File(ctx.filesDir, "subs/$id.data")

    fun subscriptions(ctx: Context): List<Subscription> = try {
        val arr = org.json.JSONArray(subsJsonFile(ctx).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Subscription(
                o.getString("id"),
                Kind.valueOf(o.getString("kind")),
                o.getString("name"),
                o.getString("url"),
                o.optLong("lastUpdatedMs", 0L),
                o.optInt("lastCount", -1),
            )
        }
    } catch (_: Throwable) { emptyList() }

    private fun saveSubs(ctx: Context, list: List<Subscription>) {
        val dir = File(ctx.filesDir, "subs")
        dir.mkdirs()
        val arr = org.json.JSONArray()
        for (s in list) arr.put(org.json.JSONObject().apply {
            put("id", s.id); put("kind", s.kind.name); put("name", s.name)
            put("url", s.url); put("lastUpdatedMs", s.lastUpdatedMs)
            put("lastCount", s.lastCount)
        })
        val tmp = File(dir, "subs.json.tmp")
        tmp.writeText(arr.toString(2))
        subsJsonFile(ctx).delete()
        tmp.renameTo(subsJsonFile(ctx))
    }

    /** 添加订阅并立即下载一次。返回新订阅（含条数）。失败抛异常。 */
    fun addSubscription(ctx: Context, kind: Kind, url: String, name: String): Subscription {
        val id = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
            (0..2).map { ('a' + (Math.random() * 26).toInt()) }.joinToString("")
        val dst = subDataFile(ctx, id)
        // v1.6.9 修复：下载前必须先建 subs/ 目录——首次添加订阅时目录不存在，
        // FileOutputStream 直接抛 FileNotFoundException（报错形似"无读写权限"）
        dst.parentFile?.mkdirs()
        downloadTo(url, dst)
        val n = countLines(dst)
        val displayName = name.ifBlank {
            url.substringAfterLast('/').ifBlank { url }.take(60)
        }
        val sub = Subscription(id, kind, displayName, url,
            System.currentTimeMillis(), n)
        saveSubs(ctx, subscriptions(ctx) + sub)
        revision++
        com.glasskey.ime.dev.GkLogger.log("lexmgr",
            "sub add kind=$kind n=$n rev=$revision")
        return sub
    }

    /** 删除订阅（含内容文件） */
    fun removeSubscription(ctx: Context, id: String) {
        subDataFile(ctx, id).delete()
        saveSubs(ctx, subscriptions(ctx).filterNot { it.id == id })
        revision++
    }

    /** 立即更新单个订阅（重新下载）。返回本次条数。 */
    fun updateSubscription(ctx: Context, id: String): Int {
        val subs = subscriptions(ctx)
        val sub = subs.firstOrNull { it.id == id }
            ?: throw IllegalStateException("订阅不存在")
        val dst = subDataFile(ctx, id)
        dst.parentFile?.mkdirs()   // v1.6.9：目录被清理后重建，防同款失败
        downloadTo(sub.url, dst)
        val n = countLines(dst)
        saveSubs(ctx, subs.map {
            if (it.id == id) it.copy(lastUpdatedMs = System.currentTimeMillis(), lastCount = n) else it
        })
        revision++
        return n
    }

    /** 立即更新全部订阅。返回 成功数 to 总数；单个失败不中断其它。 */
    fun updateAll(ctx: Context): Pair<Int, Int> {
        val subs = subscriptions(ctx)
        var ok = 0
        for (s in subs) {
            try {
                updateSubscription(ctx, s.id); ok++
            } catch (t: Throwable) {
                com.glasskey.ime.dev.GkLogger.log("lexmgr",
                    "sub update fail ${s.name}: ${t.message}")
            }
        }
        return ok to subs.size
    }

    /** 某类型的订阅内容文件（按订阅顺序） */
    fun subscriptionFiles(ctx: Context, kind: Kind): List<File> =
        subscriptions(ctx).filter { it.kind == kind }.map { subDataFile(ctx, it.id) }

    /**
     * 定时自动更新检查（v1.6.8）：任一订阅距其上次更新超过用户设定间隔
     * → 后台更新全部。无订阅/关闭/未到期均直接返回。
     * 调用点：词库管理页打开时 + 输入法服务 onCreate（键盘每天至少弹一次，
     * 等效于每日轮询；无需常驻后台任务）。
     */
    fun maybeAutoUpdate(ctx: Context) {
        val minutes = GkPrefs.subAutoUpdateMinutes(ctx)
        if (minutes <= 0) return
        val subs = subscriptions(ctx)
        if (subs.isEmpty()) return
        val last = subs.maxOf { it.lastUpdatedMs }
        if (last > 0 && System.currentTimeMillis() - last < minutes * 60_000L) return
        Thread {
            try {
                val (ok, total) = updateAll(ctx)
                com.glasskey.ime.dev.GkLogger.log("lexmgr",
                    "sub auto-update ok=$ok/$total rev=$revision")
            } catch (_: Throwable) { }
        }.apply { name = "gk-sub-auto"; isDaemon = true }.start()
    }
}
