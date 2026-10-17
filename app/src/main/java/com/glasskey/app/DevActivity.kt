package com.glasskey.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.glasskey.ime.dev.GkLogger
import com.glasskey.ime.ui.Theme

/**
 * v1.5.8 开发者设置页：
 *  - 「记录运行日志」开关卡片（与「查看日志」卡片可分别独立点击）；
 *  - 「查看运行日志」卡片：点击进入日志视图（环形缓冲 500 行）+ 复制/清空；
 *    上次停留在日志视图时再次进入直接显示日志（GkPrefs.devLogOpen 记忆）。
 */
class DevActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private var logText: TextView? = null
    private var logScroll: android.widget.ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        GkLogger.enabled = GkPrefs.devLogEnabled(this)
        buildUi(showLog = GkPrefs.devLogOpen(this))
    }

    private fun buildUi(showLog: Boolean) {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF10161D.toInt() else 0xFFF5F7FB.toInt()
        val cardBg = if (dark) 0xFF1A222C.toInt() else 0xFFFFFFFF.toInt()
        val titleC = if (dark) 0xFFEDF2F8.toInt() else 0xFF16202B.toInt()
        val subC = if (dark) 0xFF9AA8B6.toInt() else 0xFF7A8694.toInt()
        val accent = Theme.accentLight(GkPrefs.accentIndex(this))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val centerWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        centerWrap.addView(root, LinearLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels, (d * 640).toInt()),
            LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(android.widget.ScrollView(this).apply {
            addView(centerWrap)
            background = GradientDrawable().apply { setColor(bg) }
        })

        root.addView(text("开发者选项", 32f, titleC, bold = true).apply {
            setPadding(0, (d * 18).toInt(), 0, (d * 4).toInt())
        })
        root.addView(text("仅用于问题排查调试；日志仅保存在内存（≤500 行），不上传。", 13f, subC).apply {
            setPadding(0, 0, 0, (d * 14).toInt())
        })

        // —— 开关卡片（可独立点击）——
        val switchCard = card(cardBg)
        switchCard.addView(switchRow("记录运行日志", "输入法运行事件写入内存环形缓冲",
            GkPrefs.devLogEnabled(this), titleC, subC) { on ->
            GkPrefs.setDevLogEnabled(this, on)
            GkLogger.enabled = on
            if (on) GkLogger.log("dev", "日志记录已开启")
            toast(if (on) "已开启（键盘操作后回到本页查看）" else "已关闭")
        })
        root.addView(switchCard, matchWrap())

        // —— 查看日志卡片（可独立点击，直接显示日志）——
        val viewCard = card(cardBg)
        viewCard.addView(text("查看运行日志（${GkLogger.count()} 行）", 16f, titleC, bold = true))
        viewCard.addView(text("点此卡片展开/收起日志内容，可全选复制", 12f, subC).apply {
            setPadding(0, (d * 2).toInt(), 0, 0)
        })
        val scroll = android.widget.ScrollView(this).apply { visibility = android.view.View.GONE }
        val tv = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(if (dark) 0xFFB8C4D0.toInt() else 0xFF3A4652.toInt())
            setTextIsSelectable(true)
        }
        scroll.addView(tv, matchWrap().apply { topMargin = (d * 8).toInt() })
        logText = tv; logScroll = scroll
        viewCard.addView(scroll, matchWrap().apply { topMargin = (d * 6).toInt() })
        viewCard.setOnClickListener {
            val show = scroll.visibility == android.view.View.GONE
            scroll.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            if (show) refreshLog()
            GkPrefs.setDevLogOpen(this, show)
        }
        root.addView(viewCard, matchWrap().apply { topMargin = (d * 10).toInt() })

        root.addView(pillButton("复制全部日志", accent, cardBg).apply {
            setOnClickListener {
                val log = GkLogger.dump()
                if (log.isEmpty()) toast("暂无日志")
                else {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("gk-log", log))
                    toast("已复制 ${log.lines().size} 行日志")
                }
            }
        }, matchWrap().apply { topMargin = (d * 10).toInt() })

        root.addView(pillButton("清空日志", 0xFFD24D57.toInt(), cardBg).apply {
            setOnClickListener { GkLogger.clear(); refreshLog(); toast("已清空") }
        }, matchWrap().apply { topMargin = (d * 8).toInt() })

        // 上次停留在日志视图 → 直接展开
        if (showLog) {
            scroll.visibility = android.view.View.VISIBLE
            tv.post { refreshLog() }
        }
    }

    private fun refreshLog() {
        logText?.text = GkLogger.dump().ifEmpty { "（暂无日志，先开启「记录运行日志」，回键盘操作几下再回来）" }
        logScroll?.post { logScroll?.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ── M3E 组件 ──

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(bg: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt())
        background = GradientDrawable().apply { cornerRadius = d * 24f; setColor(bg) }
    }

    private fun switchRow(
        title: String, summary: String?, checked: Boolean,
        titleC: Int, subC: Int, onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, titleC))
        if (summary != null) texts.addView(text(summary, 12f, subC))
        val sw = Switch(this).apply { isChecked = checked }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        return row
    }

    private fun pillButton(label: String, accent: Int, cardBg: Int): TextView =
        TextView(this).apply {
            this.text = label; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding((d * 16).toInt(), (d * 10).toInt(), (d * 16).toInt(), (d * 10).toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(accent and 0x1AFFFFFF or 0x14000000.toInt())
                setStroke((d * 1.4f).toInt(), accent)
            }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
