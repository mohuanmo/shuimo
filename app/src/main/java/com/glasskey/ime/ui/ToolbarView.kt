package com.glasskey.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 工具栏（§5 功能入口行）：剪贴板 / 手写 / 语音 / 悬浮 / 符号 / 收起。
 * 磨砂玻璃风格文本按钮；布局为 M2 功能面板的统一入口（§5.5-5.8）。
 */
class ToolbarView(context: Context) : HorizontalScrollView(context) {

    /** 工具动作回调 */
    var onTool: ((Tool) -> Unit)? = null

    /** 主题色板（由 IME 服务按系统深浅色注入，§6.2） */
    var theme: Theme = Theme.LIGHT
        set(value) {
            field = value
            refresh()
        }

    /** 悬浮模式激活时高亮"悬浮"键 */
    var floatingActive: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    enum class Tool { CLIP, HWR, VOICE, FLOAT, SYMBOL, HIDE }

    private val d = resources.displayMetrics.density
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val buttons = mutableListOf<Pair<TextView, Tool>>()

    init {
        isHorizontalScrollBarEnabled = false
        // 图标 + 文字标签双行（真机反馈：纯 emoji 图标太淡且含义不明）
        val specs = listOf(
            "📋\n剪贴" to Tool.CLIP,
            "✍\n手写" to Tool.HWR,
            "🎙\n语音" to Tool.VOICE,
            "▣\n悬浮" to Tool.FLOAT,
            "#+=\n符号" to Tool.SYMBOL,
            "⌄\n收起" to Tool.HIDE,
        )
        for ((label, tool) in specs) {
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                setLineSpacing(0f, 0.1f)
                isClickable = true
                // 修复：此处原调用了 performClick() 造成无限递归（StackOverflow → IME 崩溃收起，
                // 真机表现为"点了没反应/键盘收起再弹出像没变化"）
                setOnClickListener { onTool?.invoke(tool) }
                // 按压变暗反馈
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.45f
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                    }
                    false
                }
            }
            val lp = LinearLayout.LayoutParams(
                (d * 60).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            lp.marginStart = (d * 4).toInt()
            lp.marginEnd = (d * 4).toInt()
            row.addView(tv, lp)
            buttons.add(tv to tool)
        }
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        refresh()
    }

    private fun refresh() {
        for ((tv, tool) in buttons) {
            val active = (tool == Tool.FLOAT && floatingActive)
            tv.background = GradientDrawable().apply {
                // v1.5 M3E：全胶囊按钮（999 半径），激活态 filled tonal
                cornerRadius = d * 999f
                setColor(if (active) theme.keyBgPressed else Color.TRANSPARENT)
                if (active) setStroke((d * 1.2f).toInt(), theme.accent)
            }
            // 非激活态也用主文字色（原 candidateHint 太淡，真机看不清）
            tv.setTextColor(if (active) theme.accent else theme.keyText)
            tv.alpha = if (active) 1f else 0.85f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((d * 34f).toInt(), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
