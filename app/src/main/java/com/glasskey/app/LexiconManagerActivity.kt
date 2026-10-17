package com.glasskey.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.glasskey.ime.ui.Theme
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * v1.6.0 词库管理页（设置入口）：
 *  - 两张数据卡：「组句词库」条数 / 「语料词对」条数；
 *  - 点击卡片 → 预览（前 300 行），可再点「查看完整内容」看全文；
 *  - 顶栏右侧（返回键对面）「订阅」按钮 → 导入：本地文件 或 网络链接，
 *    选择目标（词库 / 词对）后后台导入，完成后刷新计数；
 *    导入数据可单独清除（恢复内置）。
 */
class LexiconManagerActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var lexCountView: TextView
    private lateinit var biCountView: TextView
    private var dark = false
    private var cardBg = 0
    private var titleC = 0
    private var subC = 0
    private var accent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        LexiconStore.deployBuiltins(this)
        // v1.6.8：打开本页即检查订阅是否到期（到期后台自动更新）
        LexiconStore.maybeAutoUpdate(this)
        buildUi()
    }

    private fun buildUi() {
        dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF10161D.toInt() else 0xFFF5F7FB.toInt()
        cardBg = if (dark) 0xFF1A222C.toInt() else 0xFFFFFFFF.toInt()
        titleC = if (dark) 0xFFEDF2F8.toInt() else 0xFF16202B.toInt()
        subC = if (dark) 0xFF9AA8B6.toInt() else 0xFF7A8694.toInt()
        accent = Theme.accentLight(GkPrefs.accentIndex(this))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val centerWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        centerWrap.addView(root, LinearLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels, (d * 640).toInt()),
            LinearLayout.LayoutParams.MATCH_PARENT))

        // —— 顶栏：返回 | 标题 | 订阅（与返回键平齐的相反一侧）——
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((d * 8).toInt(), (d * 10).toInt(), (d * 8).toInt(), 0)
        }
        val back = TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(titleC)
            setPadding((d * 10).toInt(), (d * 6).toInt(), (d * 10).toInt(), (d * 6).toInt())
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "词库管理"; textSize = 20f; setTextColor(titleC)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((d * 6).toInt(), 0, 0, 0)
        }
        val subscribe = TextView(this).apply {
            text = "订阅"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding((d * 16).toInt(), (d * 7).toInt(), (d * 16).toInt(), (d * 7).toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(Color.TRANSPARENT)
                setStroke((d * 1.4f).toInt(), accent)
            }
            setOnClickListener { showSubscribeDialog() }
        }
        topBar.addView(back)
        topBar.addView(title, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(subscribe)

        val page = ScrollView(this).apply { background = GradientDrawable().apply { setColor(bg) } }
        page.addView(centerWrap)
        setContentView(page)

        root.addView(topBar, matchWrap())
        root.addView(View(this), matchWrap().apply { height = (d * 8).toInt() })

        root.addView(text("管理组句词库与语料词对数据；可预览内容，或通过右上角「订阅」从本地文件 / 网络链接导入自己的数据。", 13f, subC).apply {
            setPadding(0, 0, 0, (d * 14).toInt())
        })

        // —— 组句词库卡片 ——
        lexCountView = TextView(this)
        val lexCard = card()
        lexCard.addView(text("组句词库", 17f, titleC, bold = true))
        lexCountView.text = "${LexiconStore.lexiconCount(this)} 条 · " +
            LexiconStore.fileSizeText(LexiconStore.lexiconFile(this))
        lexCountView.textSize = 13f; lexCountView.setTextColor(subC)
        lexCard.addView(lexCountView)
        lexCard.addView(text("点击预览内容（词  拼音  词频）", 12f, subC).apply {
            setPadding(0, (d * 2).toInt(), 0, 0)
        })
        lexCard.setOnClickListener { showPreview(LexiconStore.Kind.LEXICON, "组句词库") }
        root.addView(lexCard, matchWrap().apply { topMargin = (d * 10).toInt() })

        // —— 语料词对卡片 ——
        biCountView = TextView(this)
        val biCard = card()
        biCard.addView(text("语料词对", 17f, titleC, bold = true))
        biCountView.text = "${LexiconStore.bigramCount(this)} 条 · " +
            LexiconStore.fileSizeText(LexiconStore.bigramFile(this))
        biCountView.textSize = 13f; biCountView.setTextColor(subC)
        biCard.addView(biCountView)
        biCard.addView(text("点击预览内容（词1  词2  共现次数）", 12f, subC).apply {
            setPadding(0, (d * 2).toInt(), 0, 0)
        })
        biCard.setOnClickListener { showPreview(LexiconStore.Kind.BIGRAM, "语料词对") }
        root.addView(biCard, matchWrap().apply { topMargin = (d * 10).toInt() })

        root.addView(View(this), matchWrap().apply { height = (d * 24).toInt() })
    }

    // —— 预览 / 完整查看 ——

    private fun showPreview(kind: LexiconStore.Kind, title: String) {
        val preview = LexiconStore.content(this, kind, full = false)
        val lines = preview.count { it == '\n' }
        val total = when (kind) {
            LexiconStore.Kind.LEXICON -> LexiconStore.lexiconCount(this)
            LexiconStore.Kind.BIGRAM -> LexiconStore.bigramCount(this)
        }
        val tv = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(if (dark) 0xFFB8C4D0.toInt() else 0xFF3A4652.toInt())
            setTextIsSelectable(true)
            text = preview.ifEmpty { "（暂无数据，可通过「订阅」导入）" }
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val fullBtn = if (lines < total) "查看完整内容（共 $total 条）" else null
        AlertDialog.Builder(this)
            .setTitle("$title · 预览前 $lines 条")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .apply {
                if (fullBtn != null) setNeutralButton(fullBtn) { _, _ ->
                    showFull(kind, title)
                }
            }
            .show()
    }

    private fun showFull(kind: LexiconStore.Kind, title: String) {
        thread {
            val full = LexiconStore.content(this, kind, full = true)
            runOnUiThread {
                val tv = TextView(this).apply {
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (dark) 0xFFB8C4D0.toInt() else 0xFF3A4652.toInt())
                    setTextIsSelectable(true)
                    text = full
                }
                val scroll = ScrollView(this).apply { addView(tv) }
                AlertDialog.Builder(this)
                    .setTitle("$title · 完整内容")
                    .setView(scroll)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
    }

    // —— 订阅管理（v1.6.8：链接式订阅列表）——

    private fun showSubscribeDialog() {
        val subs = LexiconStore.subscriptions(this)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(wrap)
        val pad = (d * 20).toInt()

        // —— 概览：订阅数 / 上次更新时间 / 文件数 ——
        val lastUpdate = subs.maxOfOrNull { it.lastUpdatedMs } ?: 0L
        val lastText = if (lastUpdate == 0L) "从未更新"
            else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.CHINESE).format(java.util.Date(lastUpdate))
        val autoMin = GkPrefs.subAutoUpdateMinutes(this)
        val autoText = when {
            autoMin <= 0 -> "已关闭"
            autoMin < 60 -> "每 $autoMin 分钟"
            autoMin == 60 -> "每小时"
            autoMin % 1440 == 0 -> "每 ${autoMin / 1440} 天"
            autoMin % 60 == 0 -> "每 ${autoMin / 60} 小时"
            else -> "每 $autoMin 分钟"
        }
        wrap.addView(text(
            "已订阅 ${subs.size} 个 · 上次更新：$lastText · 提供 ${subs.size} 个文件\n自动更新：$autoText",
            13f, subC).apply { setPadding(pad, (d * 4).toInt(), pad, (d * 10).toInt()) })

        // —— 订阅列表（每行：名称/类型 + URL + 上次更新 · 条数，点击弹操作）——
        if (subs.isEmpty()) {
            wrap.addView(text("暂无订阅。\n点下方「添加订阅」粘贴数据文件直链（见帮助），\n或继续使用「从本地文件导入」。", 13f, subC)
                .apply { setPadding(pad, 0, pad, (d * 8).toInt()) })
        }
        for (s in subs) {
            val kindName = if (s.kind == LexiconStore.Kind.LEXICON) "组句词库" else "语料词对"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (d * 10).toInt(), pad, (d * 10).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = d * 14f
                    setColor(cardBg)
                }
                setOnClickListener {
                    val ops = arrayOf("立即更新此订阅", "删除订阅")
                    AlertDialog.Builder(this@LexiconManagerActivity)
                        .setTitle(s.name)
                        .setItems(ops) { _, which ->
                            when (which) {
                                0 -> doSubUpdate(s.id)
                                1 -> {
                                    LexiconStore.removeSubscription(this@LexiconManagerActivity, s.id)
                                    toast("已删除订阅")
                                    rebuildAfterSubChange()
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            row.addView(text("${s.name}（$kindName）", 14f, titleC, bold = true))
            row.addView(text(s.url, 11f, subC).apply { setPadding(0, (d * 2).toInt(), 0, 0) })
            val st = if (s.lastUpdatedMs == 0L) "未更新过"
                else "上次更新 " + java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.CHINESE).format(java.util.Date(s.lastUpdatedMs)) +
                    " · ${if (s.lastCount >= 0) "${s.lastCount} 条" else "-"}"
            row.addView(text(st, 12f, subC).apply { setPadding(0, (d * 3).toInt(), 0, 0) })
            wrap.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = pad; marginEnd = pad; bottomMargin = (d * 8).toInt() })
        }

        // —— 操作按钮行 ——
        fun btn(label: String, block: () -> Unit): TextView = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding((d * 14).toInt(), (d * 9).toInt(), (d * 14).toInt(), (d * 9).toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(Color.TRANSPARENT)
                setStroke((d * 1.4f).toInt(), accent)
            }
            setOnClickListener { block() }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(pad, (d * 6).toInt(), pad, (d * 4).toInt())
        }
        btnRow.addView(btn("立即更新") { doSubUpdateAll() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = (d * 8).toInt() })
        btnRow.addView(btn("添加订阅") { addSubscriptionFlow() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = (d * 8).toInt() })
        btnRow.addView(btn("自动更新") { showAutoUpdateDialog() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = (d * 8).toInt() })
        btnRow.addView(btn("帮助") { showHelp() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(btnRow, matchWrap())

        AlertDialog.Builder(this)
            .setTitle("订阅管理")
            .setView(scroll)
            .setPositiveButton("完成") { _, _ -> refreshCounts() }
            .setNeutralButton("本地导入") { _, _ -> showSubscribeDialogLegacy() }
            .show()
    }

    /** 旧版直选对话框（本地文件导入入口保留在「本地导入」） */
    private fun showSubscribeDialogLegacy() {
        val kinds = arrayOf("导入组句词库（本地文件）", "导入语料词对（本地文件）", "帮助（用途与导入格式）")
        AlertDialog.Builder(this)
            .setTitle("本地导入")
            .setItems(kinds) { _, which ->
                when (which) {
                    0 -> showSourceDialog(LexiconStore.Kind.LEXICON)
                    1 -> showSourceDialog(LexiconStore.Kind.BIGRAM)
                    2 -> showHelp()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 订阅自动更新间隔选择（v1.6.8：用户可选，含自定义分钟） */
    private fun showAutoUpdateDialog() {
        val options = arrayOf("关闭", "每小时", "每 6 小时", "每天（默认）", "每 3 天", "每 7 天", "自定义（分钟）")
        val values = intArrayOf(0, 60, 360, 1440, 4320, 10080, -1)
        val cur = GkPrefs.subAutoUpdateMinutes(this)
        val checked = values.indexOfFirst { it == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("定时自动更新")
            .setSingleChoiceItems(options, checked) { dlg, which ->
                if (values[which] == -1) {
                    dlg.dismiss()
                    val input = EditText(this).apply {
                        hint = "间隔（分钟，如 30 / 90 / 720）"; setSingleLine()
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
                    val wrap2 = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((d * 20).toInt(), (d * 8).toInt(), (d * 20).toInt(), 0)
                    }
                    wrap2.addView(input)
                    AlertDialog.Builder(this)
                        .setTitle("自定义更新间隔")
                        .setView(wrap2)
                        .setPositiveButton("确定") { _, _ ->
                            val v = input.text.toString().toIntOrNull()?.coerceIn(5, 525600) ?: 1440
                            GkPrefs.setSubAutoUpdateMinutes(this, v)
                            toast("自动更新：每 $v 分钟")
                            rebuildAfterSubChange()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    GkPrefs.setSubAutoUpdateMinutes(this, values[which])
                    dlg.dismiss()
                    toast(if (values[which] == 0) "自动更新已关闭" else "自动更新：${options[which]}")
                    rebuildAfterSubChange()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doSubUpdateAll() {
        val subs = LexiconStore.subscriptions(this)
        if (subs.isEmpty()) { toast("暂无订阅"); return }
        toast("更新中（${subs.size} 个订阅）…")
        thread {
            val (ok, total) = try {
                LexiconStore.updateAll(this)
            } catch (t: Throwable) { 0 to subs.size }
            runOnUiThread {
                toast(if (ok == total) "全部更新完成（$ok/$total）" else "更新完成 $ok/$total（部分失败）")
                rebuildAfterSubChange()
            }
        }
    }

    private fun doSubUpdate(id: String) {
        toast("更新中…")
        thread {
            try {
                val n = LexiconStore.updateSubscription(this, id)
                runOnUiThread { toast("更新完成：$n 条"); rebuildAfterSubChange() }
            } catch (t: Throwable) {
                runOnUiThread { toast("更新失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /** 订阅变化后重建面板（简单可靠：重开同一个对话框） */
    private fun rebuildAfterSubChange() {
        refreshCounts()
        showSubscribeDialog()
    }

    /** 添加订阅流程：选类型 → 粘贴直链 → 立即下载建立订阅（v1.6.8） */
    private fun addSubscriptionFlow() {
        val kinds = arrayOf("组句词库", "语料词对")
        AlertDialog.Builder(this)
            .setTitle("添加订阅 · 选择类型")
            .setItems(kinds) { _, which ->
                val kind = if (which == 0) LexiconStore.Kind.LEXICON else LexiconStore.Kind.BIGRAM
                showSubUrlDialog(kind)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSubUrlDialog(kind: LexiconStore.Kind) {
        val kindName = if (kind == LexiconStore.Kind.LEXICON) "组句词库" else "语料词对"
        val input = EditText(this).apply {
            hint = "https://raw.githubusercontent.com/you/repo/main/lexicon.tsv"
            setSingleLine()
        }
        val nameInput = EditText(this).apply {
            hint = "订阅名称（可留空，默认取文件名）"; setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 20).toInt(), (d * 8).toInt(), (d * 20).toInt(), 0)
        }
        wrap.addView(TextView(this).apply {
            text = "粘贴指向 $kindName 数据文件的直链（TXT/TSV），添加后立即下载一次，此后可手动/定时更新"
            textSize = 12f; setTextColor(subC)
        })
        wrap.addView(input, matchWrap().apply { topMargin = (d * 8).toInt() })
        wrap.addView(nameInput, matchWrap().apply { topMargin = (d * 8).toInt() })
        AlertDialog.Builder(this)
            .setTitle("添加订阅")
            .setView(wrap)
            .setPositiveButton("添加") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) { toast("链接为空"); return@setPositiveButton }
                val name = nameInput.text.toString().trim()
                toast("添加订阅中…")
                thread {
                    try {
                        val sub = LexiconStore.addSubscription(this, kind, url, name)
                        runOnUiThread {
                            toast("订阅添加成功：${sub.lastCount} 条")
                            rebuildAfterSubChange()
                        }
                    } catch (t: Throwable) {
                        runOnUiThread { toast("添加失败：${t.message ?: t.javaClass.simpleName}") }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v1.6.3：帮助文档——数据用途 + 导入格式说明 */
    private fun showHelp() {
        val msg = """
            这两个数据是干什么的？

            ① 组句词库：长句连续拼音（连打/首字母/混拼）时，
               把你的拼音切成词并组合成句子的词表。
               条目越多、覆盖越广，长句候选越准。

            ② 语料词对：记录“哪个词后面常接哪个词”的共现次数。
               组句时用它分辨同音歧义（例如“开始讨论”vs“开时讨论”，
               词频几乎分不出胜负，但“开始”后接“讨论”在真实语料里
               常见得多）。词对越丰富，整句首选准确率越高。

            ── 导入格式 ──

            组句词库（TXT/TSV，每行一条，TAB 分隔）：
              词条<TAB>拼音<TAB>词频
            示例：
              开始	kai shi	17500
              讨论	tao lun	32046
            · 拼音可写连体（kaishi），引擎会自动切分音节
            · 词频可省略（默认 1）；可无空格或用分隔符均可

            语料词对（TXT/TSV，每行一条，空格分隔）：
              词1 词2 共现次数
            示例：
              开始 讨论 1523
              非常 感谢 980

            ── 注意 ──

            · 文件编码需为 UTF-8
            · 单次导入会覆盖上一次导入的同类数据（内置数据不受影响），
              可用“清除已导入”恢复内置
            · 订阅：可添加多个链接订阅，每个订阅独立文件互不覆盖；
              支持“立即更新”与定时自动更新（间隔自选）
            · 网络导入/订阅需要文件的直链（浏览器能直接打开下载的那种）
            · 全程本机处理，导入的数据不会上传
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("词库管理 · 帮助")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showSourceDialog(kind: LexiconStore.Kind) {
        val kindName = if (kind == LexiconStore.Kind.LEXICON) "组句词库" else "语料词对"
        val options = arrayOf("从本地文件导入", "从网络链接导入",
            "清除已导入的$kindName（恢复内置）")
        AlertDialog.Builder(this)
            .setTitle("$kindName · 数据来源")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickLocal(kind)
                    1 -> showUrlDialog(kind)
                    2 -> {
                        LexiconStore.clearImported(this, kind)
                        refreshCounts()
                        toast("已清除导入数据")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private var pickingKind = LexiconStore.Kind.LEXICON

    private fun pickLocal(kind: LexiconStore.Kind) {
        pickingKind = kind
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            toast("无法打开文件选择器：${t.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            doImport(pickingKind) { LexiconStore.importFromUri(this, pickingKind, uri) }
        }
    }

    private fun showUrlDialog(kind: LexiconStore.Kind) {
        val kindName = if (kind == LexiconStore.Kind.LEXICON) "组句词库" else "语料词对"
        val input = EditText(this).apply {
            hint = "https://example.com/data.tsv"
            setSingleLine()
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 20).toInt(), (d * 8).toInt(), (d * 20).toInt(), 0)
        }
        wrap.addView(TextView(this).apply {
            text = "粘贴指向 $kindName 数据文件的链接（TXT/TSV 直链，导入后覆盖此前导入的数据）"
            textSize = 12f; setTextColor(subC)
        })
        wrap.addView(input, matchWrap().apply { topMargin = (d * 8).toInt() })
        AlertDialog.Builder(this)
            .setTitle("网络链接导入")
            .setView(wrap)
            .setPositiveButton("下载导入") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) { toast("链接为空"); return@setPositiveButton }
                doImport(kind) { LexiconStore.importFromUrl(this, kind, url) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doImport(kind: LexiconStore.Kind, block: () -> Int) {
        toast("导入中…")
        thread {
            try {
                val n = block()
                runOnUiThread {
                    refreshCounts()
                    val kindName = if (kind == LexiconStore.Kind.LEXICON) "组句词库" else "语料词对"
                    toast("$kindName 导入完成：$n 条")
                    com.glasskey.ime.dev.GkLogger.log(
                        "lexmgr", "import $kind n=$n rev=${LexiconStore.revision}")
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("导入失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    private fun refreshCounts() {
        lexCountView.text = "${LexiconStore.lexiconCount(this)} 条 · " +
            LexiconStore.fileSizeText(LexiconStore.lexiconFile(this))
        biCountView.text = "${LexiconStore.bigramCount(this)} 条 · " +
            LexiconStore.fileSizeText(LexiconStore.bigramFile(this))
    }

    // ── M3E 组件 ──

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt())
        background = GradientDrawable().apply { cornerRadius = d * 24f; setColor(cardBg) }
        isClickable = true
        isFocusable = true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private companion object { const val REQ_PICK = 41 }
}
