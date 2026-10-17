package com.glasskey.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 候选条 v1.6.1 —— 两层结构（用户需求：原字母与候选分层）：
 *
 *   ┌──────────────────────────────────────┐  上层（合成层）
 *   │ kai'shi'tao'lun   ← 原字母 + 分词标记 │  （引擎分词处显示高亮「'」）
 *   ├────┬─────────────────────────────────┤  下层（候选层）
 *   │分词│ 开始  开时  开市  …  （横滑）      │  （左端「分词」实体键 + 候选）
 *   └────┴─────────────────────────────────┘
 *
 * v1.6.1 变更：
 *  - 上层独占一行显示合成原文字母（不再与候选同层挤占），分词符「'」高亮加粗；
 *  - 「分词」按钮从合成框内移到下层最左端（键帽样式，与回车/删除同观感，靠左）；
 *  - 下层候选行保持原有宽度高度与横滑行为。
 * 交互不变：上层点击 = 原文上屏；上层长按 = 清空合成区；分词键 = 手动分词锚定。
 */
class CandidateBar(context: Context) : FrameLayout(context) {

    var theme: Theme = Theme.LIGHT
        set(value) {
            field = value
            refreshTheme()
        }

    var onPick: ((Int) -> Unit)? = null
    var onComposingCommit: (() -> Unit)? = null   // 合成区点击 → 原文上屏
    var onComposingClear: (() -> Unit)? = null    // 合成区长按 → 清空合成区
    var onSplit: (() -> Unit)? = null             // 分词按钮 → 手动分词锚定（v1.2）

    /** 上层：合成层（原字母 + 分词标记） */
    private val composingRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    private val composingView = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setSingleLine()
        maxLines = 1
    }

    /** 下层：候选行（左端分词键 + 横滑候选） */
    private val candidateRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 手动分词键（v1.6.1：下层最左端键帽样式） */
    private val splitBtn = TextView(context).apply {
        text = "分词"
        gravity = Gravity.CENTER
        setSingleLine()
        visibility = View.GONE
    }

    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
    }

    private val chipsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val d = resources.displayMetrics.density
    private var chipTexts: List<String> = emptyList()

    /** 候选字号（sp，v1.5 设置界面自定义 14..22） */
    var textSizeSp: Int = 18
        set(value) {
            field = value
            val cur = chipTexts
            refreshTheme()
            if (cur.isNotEmpty()) setCandidates(cur)
        }

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        composingRow.addView(composingView, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        candidateRow.addView(splitBtn, LinearLayout.LayoutParams(
            (d * 52).toInt(), LayoutParams.MATCH_PARENT).apply {
            marginEnd = (d * 4).toInt()
        })
        candidateRow.addView(scroller, LinearLayout.LayoutParams(
            0, LayoutParams.MATCH_PARENT, 1f))
        scroller.addView(chipsRow, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        column.addView(composingRow, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, (d * 26).toInt()))
        column.addView(candidateRow, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshTheme()
    }

    fun setComposing(text: String) {
        // v1.6.4：合成层恒定占位——空输入时也显示空框（悬浮模式/刚开始输入时
        // 屏幕不再因多出一层而跳一下；两层高度从键盘弹出起就固定）
        composingView.text = stylizeComposing(text)
        composingRow.visibility = VISIBLE
        composingView.hint = if (text.isEmpty()) " " else null
        // 候选更新时回到最左（新输入惯例）
        scroller.smoothScrollTo(0, 0)
        requestLayout()
    }

    /** 分词符「'」高亮加粗（引擎 segmentedInput 的分隔符），分词点一眼可见 */
    private fun stylizeComposing(text: String): CharSequence {
        if (!text.contains('\'')) return text
        val sb = SpannableStringBuilder()
        var i = 0
        for (part in text.split('\'')) {
            if (i > 0) {
                val start = sb.length
                sb.append("'")
                sb.setSpan(ForegroundColorSpan(theme.accent), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(part)
            i++
        }
        return sb
    }

    /** 分词按钮可见性（拼音合成中可见；空串/英文模式隐藏） */
    fun setSplitVisible(visible: Boolean) {
        splitBtn.visibility = if (visible) VISIBLE else GONE
    }

    fun setCandidates(list: List<String>) {
        chipTexts = list
        chipsRow.removeAllViews()
        list.forEachIndexed { i, text ->
            val chip = TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                setSingleLine()
                maxWidth = (d * 180).toInt()
                setPadding((d * 12).toInt(), 0, (d * 12).toInt(), 0)
                // 修复：此处原调用了 performClick() 造成无限递归（StackOverflow → IME 崩溃收起）
                setOnClickListener { onPick?.invoke(i) }
                // 按压变暗反馈（真机反馈：点击无 UI 感知）
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.45f
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                    }
                    false   // 不消费，继续走点击
                }
            }
            val lp = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            lp.marginEnd = (d * 2).toInt()
            chipsRow.addView(chip, lp)
            styleChip(chip, i == 0)
        }
        scroller.smoothScrollTo(0, 0)
    }

    private fun styleChip(chip: TextView, primary: Boolean) {
        val base = textSizeSp.toFloat()
        chip.textSize = if (primary) base + 2f else base
        chip.setTextColor(if (primary) theme.accent else theme.candidateText)
        chip.background = GradientDrawable().apply {
            // v1.5 M3E：主候选 = filled tonal 胶囊（accent 18% 填充，无描边）；
            // 其余候选 = 无底纯文字（expressive list）
            cornerRadius = d * 999f
            if (primary) {
                val a = Color.argb(46, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
                setColor(a)
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
    }

    private fun refreshTheme() {
        // 上层合成层：整行底色框（与键盘面板区分），分词符由 Span 高亮
        composingRow.background = GradientDrawable().apply {
            cornerRadius = d * 10f
            setColor(theme.keyBgPressed)
            setStroke((d * 1).toInt(), theme.divider)
        }
        composingRow.setPadding((d * 10).toInt(), 0, (d * 10).toInt(), 0)
        composingView.setTextColor(theme.candidateHint)
        composingView.textSize = 14f
        composingView.isClickable = true
        composingView.isLongClickable = true
        composingView.setOnClickListener { onComposingCommit?.invoke() }
        composingView.setOnLongClickListener {
            onComposingClear?.invoke()
            true
        }
        // 分词键：键帽样式（v1.6.1 下层最左端，与回车/删除同观感）
        splitBtn.textSize = 13f
        splitBtn.setTextColor(theme.keyText)
        splitBtn.background = GradientDrawable().apply {
            cornerRadius = d * 12f
            setColor(theme.keyBgFn)
            setStroke((d * 1).toInt(), theme.divider)
        }
        splitBtn.isClickable = true
        splitBtn.setOnClickListener { onSplit?.invoke() }
        // 重建 chips 以应用新主题
        val current = chipTexts
        if (current.isNotEmpty()) setCandidates(current)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // v1.6.4 两层恒定：候选层 44dp + 合成层占位 26dp（空输入也占位，高度不再跳变）
        val h = resolveSize((d * 44f).toInt() + (d * 26f).toInt(), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
