package com.glasskey.ime.clipboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.glasskey.ime.ui.Theme

/**
 * 剪贴板历史面板（§5.8）：最近 20 条；置顶固定、单条上屏、置顶/删除、一键清空。
 * 布局：标题行（含清空按钮）+ 滚动列表 + 返回键盘按钮。
 */
class ClipPanel(context: Context) : ScrollView(context) {

    var onCommit: ((String) -> Unit)? = null          // 条目点击 → 上屏
    var onBack: (() -> Unit)? = null                  // 返回键盘

    private val d = resources.displayMetrics.density
    private var db: ClipDb? = null
    private var theme: Theme = Theme.LIGHT
    private val listHost = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private lateinit var clearBtn: Button

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 10).toInt(), (d * 6).toInt(), (d * 10).toInt(), (d * 6).toInt())
        }
        val title = TextView(context).apply {
            text = "剪贴板历史（最近 20 条 · 24 小时自动清理）"
            textSize = 13f
        }
        clearBtn = Button(context).apply {
            text = "清空"
            textSize = 12f
            setOnClickListener {
                db?.clear()
                Toast.makeText(context, "已清空（置顶条目保留）", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearBtn)
        }
        val backBtn = Button(context).apply {
            text = "返回键盘"
            textSize = 13f
            setOnClickListener { onBack?.invoke() }
        }
        root.addView(head)
        root.addView(listHost)
        root.addView(backBtn)
        addView(root)
    }

    fun attach(database: ClipDb, theme: Theme) {
        this.db = database
        this.theme = theme
        refresh()
    }

    fun refresh() {
        listHost.removeAllViews()
        val entries = db?.all().orEmpty()
        if (entries.isEmpty()) {
            listHost.addView(hint("暂无剪贴板历史\n复制内容后切换到此应用即可捕获"))
            return
        }
        for (e in entries) {
            listHost.addView(entryRow(e))
            if (e !== entries.last()) listHost.addView(divider())
        }
    }

    private fun entryRow(e: ClipDb.ClipEntry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (d * 6).toInt(), 0, (d * 6).toInt())
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(context).apply {
            text = (if (e.pinned) "📌 " else "") + e.preview
            textSize = 14f
            setTypeface(typeface, if (e.pinned) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 2
            setOnClickListener {
                onCommit?.invoke(e.content)
            }
        }
        val meta = TextView(context).apply {
            text = "${e.content.length} 字符 · ${kindLabel(e.content)}"
            textSize = 11f
            alpha = 0.7f
        }
        textCol.addView(title)
        textCol.addView(meta)
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun action(label: String, block: () -> Unit): Button =
            Button(context).apply {
                text = label
                textSize = 11f
                setOnClickListener { block(); refresh() }
                minimumWidth = (d * 52).toInt()
            }
        row.addView(action(if (e.pinned) "取消固定" else "置顶") {
            db?.setPinned(e.id, !e.pinned)
        })
        row.addView(action("删除") { db?.delete(e.id) })
        return row
    }

    private fun kindLabel(text: String): String = when (ClipboardModule.smartClip(text)?.kind) {
        ClipboardModule.Kind.OTP -> "验证码"
        ClipboardModule.Kind.URL -> "链接"
        ClipboardModule.Kind.PHONE -> "电话"
        else -> "文本"
    }

    private fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        alpha = 0.6f
        setPadding(0, (d * 40).toInt(), 0, (d * 40).toInt())
    }

    private fun divider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (d * 1).toInt())
        setBackgroundColor(theme.divider)
    }
}
