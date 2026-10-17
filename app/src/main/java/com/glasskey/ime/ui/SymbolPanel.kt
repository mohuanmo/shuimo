package com.glasskey.ime.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 符号面板（§5.4 / v1.5.5 扩展）：
 *  - 中文/数学符号分页（原有）；
 *  - v1.5.5：emoji 表情（Unicode 15.1 官方分类数据，assets/dict/emoji_zh.json，
 *    9 大类 × 96 + 颜文字 32，共 ~900 个，点击直接上屏；数据离线内置）。
 */
class SymbolPanel(context: Context) : LinearLayout(context) {

    var onCommit: ((String) -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val grid = GridLayout(context).apply {
        columnCount = 8
        useDefaultMargins = false
    }

    private val pages = LinkedHashMap<String, List<String>>().apply {
        put("中文", "，。！？；：、\u201C\u201D\u2018\u2019（）《》【】…—·～￥％℃°".map { it.toString() })
        put("数学", "+-×÷=≠<>≤≥±√∑∈∞π∫@#&*$^_|/\\:.".map { it.toString() })
    }
    private var emojiLoaded = false

    init {
        orientation = VERTICAL
        val title = TextView(context).apply {
            text = "符号 · 表情"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (d * 4).toInt(), 0, (d * 4).toInt())
        }
        // v1.5.5：标签页过多（12+），改横滑容器
        val tabsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val tabs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabsRow)
        }
        fun tab(label: String, page: String) {
            tabsRow.addView(Button(context).apply {
                text = label; textSize = 12f; isAllCaps = false
                setOnClickListener { loadEmojiIfNeed(); showPage(page) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        tab("中文", "中文")
        tab("数学", "数学")
        loadEmojiIfNeed()
        for (name in pages.keys) if (name !in setOf("中文", "数学")) tab(name, name)
        val back = Button(context).apply {
            text = "返回"; textSize = 12f
            setOnClickListener { onBack?.invoke() }
            tabsRow.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showPage("中文")
    }

    /** emoji 数据懒加载（assets/dict/emoji_zh.json，~8KB） */
    private fun loadEmojiIfNeed() {
        if (emojiLoaded) return
        emojiLoaded = true
        try {
            val txt = context.assets.open("dict/emoji_zh.json").bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(txt)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!pages.containsKey(k)) {
                    val arr = obj.getJSONArray(k)
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    pages[k] = list
                }
            }
        } catch (_: Throwable) { }
    }

    private fun showPage(page: String) {
        grid.removeAllViews()
        val symbols = pages[page] ?: return
        val isEmoji = page !in setOf("中文", "数学")
        for (sym in symbols) {
            val b = Button(context).apply {
                text = sym
                textSize = if (isEmoji) 18f else 16f
                includeFontPadding = false
                setOnClickListener { onCommit?.invoke(sym) }
            }
            val lp = GridLayout.LayoutParams()
            lp.width = (d * (if (isEmoji && sym.length > 2) 52 else 44)).toInt()
            lp.height = (d * (if (isEmoji) 48 else 44)).toInt()
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            grid.addView(b, lp)
        }
    }
}
