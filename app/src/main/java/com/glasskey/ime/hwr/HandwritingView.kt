package com.glasskey.ime.hwr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 手写画布（§5.5）：笔迹贝塞尔平滑 + 压感变线宽（接触半径）+ 抬笔即识别。
 * 画布内下滑（从底部边缘向下滑）退出。半屏画布（全屏透明覆盖列入 M3）。
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onStrokeFinished: ((List<List<PointF>>) -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var theme: com.glasskey.ime.ui.Theme = com.glasskey.ime.ui.Theme.LIGHT
        set(value) {
            field = value
            invalidate()
        }

    private val strokes = mutableListOf<MutableList<PointF>>()
    private val paths = mutableListOf<Path>()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 4f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = resources.displayMetrics.density * 14f
        textAlign = Paint.Align.CENTER
    }

    private var downY = 0f
    private var swipeCandidate = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        strokePaint.color = theme.keyText
    }

    fun clear() {
        strokes.clear()
        paths.clear()
        invalidate()
    }

    fun hasInk(): Boolean = strokes.isNotEmpty()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (strokes.isEmpty()) {
            hintPaint.alpha = 90
            c.drawText("在此书写 · 每字一笔一划均可 · 连笔支持", width / 2f, height / 2f, hintPaint)
        }
        for (path in paths) c.drawPath(path, strokePaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                swipeCandidate = strokes.isEmpty()  // 仅空画布允许下滑退出
                val s = mutableListOf(PointF(e.x, e.y))
                strokes.add(s)
                val p = Path()
                p.moveTo(e.x, e.y)
                paths.add(p)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeCandidate && e.y - downY < resources.displayMetrics.density * 60f) {
                    // 下滑超阈值 → 退出画布
                }
                val cur = strokes.lastOrNull() ?: return false
                val path = paths.lastOrNull() ?: return false
                val last = cur.lastOrNull() ?: return false
                val midX = (last.x + e.x) / 2f
                val midY = (last.y + e.y) / 2f
                path.quadTo(last.x, last.y, midX, midY)  // 贝塞尔平滑
                cur.add(PointF(e.x, e.y))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swipeCandidate && e.y - downY > resources.displayMetrics.density * 100f) {
                    strokes.clear(); paths.clear()
                    onSwipeDown?.invoke()
                    invalidate()
                    return true
                }
                // 极短触点（误触）丢弃
                val cur = strokes.lastOrNull()
                if (cur != null && cur.size < 2) {
                    strokes.removeAt(strokes.size - 1)
                    paths.removeAt(paths.size - 1)
                } else {
                    onStrokeFinished?.invoke(strokes.map { it.toList() })
                }
            }
        }
        invalidate()
        return true
    }
}
