package com.glasskey.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * 磨砂玻璃键盘视图（§5.1.2，传统 View 自绘 §D-05）
 * 三布局共用：pinyin26 / t9 / latin(upper)；tap 触发 onKey，退格长按连发。
 * v1.3：空格长按（≥320ms）触发语音输入（按住说话、松开停止，微信式交互）。
 */
class KeyboardView(context: Context) : View(context) {

    var keyboard: KeyboardDef = KeyboardDefs.pinyin26()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var onKey: ((Int) -> Unit)? = null

    /** 空格长按语音（v1.3）：按住 ≥SPACE_HOLD_MS 触发 start；松开/取消触发 end */
    var onSpaceHoldStart: (() -> Unit)? = null
    var onSpaceHoldEnd: (() -> Unit)? = null

    var theme: Theme = Theme.LIGHT
        set(value) {
            field = value
            invalidate()
        }

    /** 高亮键 code（如中/英键：直接显示并高亮当前语言，真机反馈切换不明显） */
    var accentCode: Int = Int.MIN_VALUE
        set(value) {
            field = value
            invalidate()
        }

    /** 键盘高度缩放（百分比 80..130，v1.5 设置界面自定义） */
    var keyHeightScale: Int = 100
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    // 布局缓存
    private var keyRects = listOf<Pair<RectF, KeyDef>>()
    private var panelBounds = RectF()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var pressedKey: KeyDef? = null
    private var pressedRect: RectF? = null

    // 空格长按语音状态（v1.3）
    private var spaceHoldRunnable: Runnable? = null
    private var spaceHoldFired = false

    // v1.5.5 回车长按 + 上滑 = 清空光标前所有文本
    var onEnterSwipeClear: (() -> Unit)? = null
    private var enterHoldRunnable: Runnable? = null
    private var enterHoldFired = false
    private var enterSwipeArmed = false
    private var enterDownY = 0f

    // v1.5.8 字母键上滑标点（Gboard 风格，设置开关控制）
    var swipePunctEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    private var letterDispatchRunnable: Runnable? = null
    private var letterDownY = 0f
    private var letterDownAt = 0L        // v1.6.6：按下时刻（flick 速度判定）
    private var letterSwipeFired = false   // 本次手势已出标点
    private var letterPendingKey: KeyDef? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout(w, h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val scale = keyHeightScale.coerceIn(80, 130) / 100f
        val h = resolveSize((resources.displayMetrics.density * ROW_H_DP * keyboard.rows.size * scale).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
        computeLayout(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    private fun computeLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val gap = d * GAP_DP
        val panelPad = d * PANEL_PAD_DP
        panelBounds = RectF(panelPad * 0.5f, 0f, w - panelPad * 0.5f, h.toFloat())
        keyRects = buildList {
            val rowH = (h - panelPad) / keyboard.rows.size
            keyboard.rows.forEachIndexed { ri, row ->
                val totalW = w - panelPad * 2 - gap * (row.keys.size - 1)
                val weightSum = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                var x = panelPad
                val yTop = panelPad + ri * rowH
                row.keys.forEach { key ->
                    val kw = (totalW * (key.weight / weightSum)).toFloat()
                    add(RectF(x, yTop, x + kw, yTop + rowH - gap) to key)
                    x += kw + gap
                }
            }
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        // v1.5 M3E：面板底部大圆角（28dp）
        FrostRenderer.drawPanel(c, panelBounds, theme, resources.displayMetrics.density * 28f)
        val d = resources.displayMetrics.density
        textPaint.textSize = d * 18f
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((rect, key) in keyRects) {
            val pressed = key === pressedKey
            // v1.5 M3E：shape morph —— 按下时圆角变大（14dp）+ 轻微收缩（scale 0.97）
            val radius = d * if (pressed) 14f else 12f
            val drawRect = if (pressed) {
                val dx = rect.width() * 0.015f
                val dy = rect.height() * 0.015f
                RectF(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy)
            } else rect
            // M3E tonal 层级：功能键用 secondary-container 底色，字符键保持 surface
            val tonal = key.isFunction || key.code == ' '.code
            if (tonal && !pressed) {
                FrostRenderer.drawKey(c, drawRect, theme.copy(keyBg = theme.keyBgFn), pressed, radius)
            } else {
                FrostRenderer.drawKey(c, drawRect, theme, pressed, radius)
            }
            if (key.code == accentCode) {
                // 高亮键：叠加 accent 半透明底 + accent 文字（当前语言一目了然）
                accentPaint.color = theme.accent
                accentPaint.alpha = if (pressed) 120 else 45
                c.drawRoundRect(drawRect, radius, radius, accentPaint)
                textPaint.color = theme.accent
                textPaint.isFakeBoldText = true
            } else {
                textPaint.color = theme.keyText
                textPaint.isFakeBoldText = !key.isFunction
            }
            // 多行标签（T9 数字+字母）
            val lines = key.label.split("\n")
            if (lines.size == 1) {
                val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                c.drawText(key.label, rect.centerX(), y, textPaint)
            } else {
                val lineH = textPaint.textSize * 1.1f
                var y = rect.centerY() - lineH * (lines.size - 1) / 2f
                textPaint.textSize = d * 14f
                for (ln in lines) {
                    c.drawText(ln, rect.centerX(), y - textPaint.ascent() / 3f, textPaint)
                    y += lineH
                }
                textPaint.textSize = d * 18f
            }
            // v1.5.8：上滑标点提示（键帽右上角小字，功能随设置开关）
            if (swipePunctEnabled) {
                val punct = if (key.code in 'a'.code..'z'.code)
                    KeyboardDefs.SWIPE_PUNCT[key.code.toChar()] else null
                if (punct != null) {
                    val hint = Paint(textPaint).apply {
                        textSize = d * 10f
                        textAlign = Paint.Align.RIGHT
                        color = theme.keyText
                        alpha = 110
                        isFakeBoldText = false
                    }
                    c.drawText(punct.toString(),
                        rect.right - d * 4f, rect.top + d * 13f, hint)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitKey(e.x, e.y) ?: return false
                pressedKey = hit.second
                pressedRect = hit.first
                invalidate()
                when {
                    hit.second.code == ' '.code -> {
                        // 空格长按 = 语音（v1.3）：DOWN 不立即上屏，延迟 320ms 判定——
                        // 超时进入语音模式；在此之前抬起则按普通空格上屏
                        spaceHoldFired = false
                        val r = Runnable {
                            spaceHoldFired = true
                            onSpaceHoldStart?.invoke()
                        }
                        spaceHoldRunnable = r
                        repeatHandler.postDelayed(r, SPACE_HOLD_MS)
                    }
                    hit.second.code == com.glasskey.ime.ui.KeyboardDefs.CODE_ENTER -> {
                        // v1.5.5：回车长按（≥320ms）进入"上滑清空"模式；
                        // 320ms 内抬起 = 普通换行
                        enterHoldFired = false
                        enterSwipeArmed = false
                        enterDownY = e.y
                        val r = Runnable { enterHoldFired = true }
                        enterHoldRunnable = r
                        repeatHandler.postDelayed(r, SPACE_HOLD_MS)
                    }
                    else -> {
                        // v1.5.8：字母键 + 上滑标点开启 → 延迟 70ms 派发（上滑抢先出标点）；
                        // 关闭或非字母键保持按下即出（零延迟手感）
                        if (swipePunctEnabled && hit.second.code in 'a'.code..'z'.code) {
                            letterPendingKey = hit.second
                            letterDownY = e.y
                            letterDownAt = System.currentTimeMillis()
                            letterSwipeFired = false
                            val r = Runnable {
                                letterDispatchRunnable = null
                                if (!letterSwipeFired) {
                                    dispatchKey(hit.second)
                                    letterPendingKey = null
                                }
                            }
                            letterDispatchRunnable = r
                            repeatHandler.postDelayed(r, LETTER_TAP_DISPATCH_MS)
                        } else {
                            dispatchKey(hit.second)
                        }
                        startRepeatIfBackspace(hit.second)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 长按判定中滑出空格 → 取消（视为误触，不上屏不录音）
                if (spaceHoldRunnable != null && !spaceHoldFired) {
                    val hit = hitKey(e.x, e.y)
                    if (hit?.second?.code != ' '.code) {
                        spaceHoldRunnable?.let { repeatHandler.removeCallbacks(it) }
                        spaceHoldRunnable = null
                        pressedKey = null
                        pressedRect = null
                        invalidate()
                    }
                }
                // v1.5.5：回长按中向上滑出按键顶部 40dp → 预备清空
                if (enterHoldFired && !enterSwipeArmed &&
                    e.y < (pressedRect?.top ?: Float.MAX_VALUE) - resources.displayMetrics.density * 40f
                ) {
                    enterSwipeArmed = true
                    invalidate()
                }
                // v1.6.6 上滑标点判定（对齐主流 flick 手势）——
                // 调研结论（Gboard "Flick keys" / iOS iPadOS Key Flicks / 搜狗
                // "上滑输入数字或符号" / 三星替代字符）：主流厂商都不做长按等待，
                // 防误触靠滑动几何（方向 + 距离 + 速度），且符号常驻键帽灰色角标提示。
                // 本实现：上滑 ≥28dp 时看滑动速度——
                //   快速轻弹（≥0.2dp/ms，即 100ms 内滑 20dp 量级）→ 出标点；
                //   长距离明确上滑（≥60dp，任意速度）→ 出标点；
                //   慢速小幅拖动 → 照常打字母。轻点（无滑动）不受影响。
                if (letterPendingKey != null && !letterSwipeFired) {
                    val d = resources.displayMetrics.density
                    val threshold = d * 28f
                    val dist = letterDownY - e.y
                    if (dist > threshold) {
                        letterDispatchRunnable?.let { repeatHandler.removeCallbacks(it) }
                        letterDispatchRunnable = null
                        letterSwipeFired = true
                        val elapsed =
                            (System.currentTimeMillis() - letterDownAt).coerceAtLeast(1)
                        val speed = dist / elapsed  // dp/ms
                        val flick = speed >= FLICK_MIN_SPEED_DP_PER_MS || dist >= d * 60f
                        if (flick) {
                            val punct = KeyboardDefs.SWIPE_PUNCT[letterPendingKey!!.code.toChar()]
                            if (punct != null) {
                                pressedKey = null
                                pressedRect = null
                                invalidate()
                                onKey?.invoke(punct.code)
                            }
                        } else {
                            // 慢速小幅拖动：按普通字母处理（滑动不算选标点手势）
                            dispatchKey(letterPendingKey!!)
                            letterPendingKey = null
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                spaceHoldRunnable?.let { repeatHandler.removeCallbacks(it) }
                spaceHoldRunnable = null
                // v1.5.8：字母延迟派发未触发（快速轻点）→ 抬手补派发
                letterDispatchRunnable?.let { repeatHandler.removeCallbacks(it) }
                letterDispatchRunnable = null
                val pending = letterPendingKey
                val swiped = letterSwipeFired
                letterPendingKey = null
                letterSwipeFired = false
                if (pending != null && !swiped && e.actionMasked == MotionEvent.ACTION_UP) {
                    dispatchKey(pending)
                }
                val wasVoice = spaceHoldFired
                spaceHoldFired = false
                pressedKey = null
                pressedRect = null
                invalidate()
                if (wasVoice && e.actionMasked == MotionEvent.ACTION_UP) {
                    onSpaceHoldEnd?.invoke()  // 松开空格 → 停止语音并上屏
                }
                // v1.5.5：回车长按上滑 → 清空光标前所有文本
                if (enterHoldRunnable != null || enterHoldFired) {
                    enterHoldRunnable?.let { repeatHandler.removeCallbacks(it) }
                    enterHoldRunnable = null
                    val fire = enterHoldFired && enterSwipeArmed &&
                        e.actionMasked == MotionEvent.ACTION_UP
                    enterHoldFired = false
                    enterSwipeArmed = false
                    if (fire) onEnterSwipeClear?.invoke()
                }
                return true
            }
        }
        return true
    }

    private fun hitKey(x: Float, y: Float): Pair<RectF, KeyDef>? {
        val pad = resources.displayMetrics.density * 4f
        for ((rect, key) in keyRects) {
            if (x >= rect.left - pad && x <= rect.right + pad && y >= rect.top && y <= rect.bottom + pad) {
                return rect to key
            }
        }
        return null
    }

    private fun dispatchKey(key: KeyDef) {
        onKey?.invoke(key.code)
    }

    private fun startRepeatIfBackspace(key: KeyDef) {
        if (key.code != KeyboardDefs.CODE_BACKSPACE) return
        val r = object : Runnable {
            override fun run() {
                dispatchKey(key)
                repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = r
        repeatHandler.postDelayed(r, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    companion object {
        const val ROW_H_DP = 52f
        const val GAP_DP = 5f
        const val PANEL_PAD_DP = 8f
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 60L

        /** 空格长按触发语音的阈值（微信式按住说话；业界惯例 300-350ms） */
        const val SPACE_HOLD_MS = 320L

        /** v1.5.8：字母键上滑标点模式下的轻点派发延迟（ms；平衡手感与抢滑判定） */
        const val LETTER_TAP_DISPATCH_MS = 70L

        /** v1.6.6：flick 判定速度阈值（dp/ms）——≥0.2 即约 100ms 内弹过 20dp，
         * 对齐 Gboard/iOS Key Flicks"快速轻扫即出、无长按等待"的手感 */
        const val FLICK_MIN_SPEED_DP_PER_MS = 0.2f
    }
}
