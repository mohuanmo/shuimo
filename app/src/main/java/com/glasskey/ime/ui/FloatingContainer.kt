package com.glasskey.ime.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮键盘容器（§5.7 路线 A 默认实现）：
 * IME 面板窗口内浮动 —— 无需任何权限申请（验收标准：路线 A 无权限申请记录）。
 * 交互：手柄拖动移动、双指缩放（80%~120%）、透明度三档循环、边缘磁吸、
 * 最小化为条、点击还原；横屏/重建时自动居中（§5.7 通用交互）。
 * 路线 B（TYPE_APPLICATION_OVERLAY 全屏悬浮）需"显示在其他应用上层"权限，
 * 由服务层检测 Settings.canDrawOverlays；拒绝时优雅降级本路线（§5.7 验收标准）。
 */
class FloatingContainer(context: Context) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private val grabber = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // v1.5 M3E：悬浮手柄胶囊化 + 顶部圆角
        background = GradientDrawable().apply {
            cornerRadius = d * 999f
            setColor(Color.argb(70, 20, 30, 40))
        }
    }
    private val box = FrameLayout(context)

    var onRestore: (() -> Unit)? = null    // 还原固定面板
    var onMinimize: ((Boolean) -> Unit)? = null

    private var scale = 1.0f
    private var alphaLevel = 1.0f
    private var minimized = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(0.7f, 1.3f)
                box.scaleX = scale
                box.scaleY = scale
                return true
            }
        })

    init {
        val handleLabel = TextView(context).apply {
            text = "≡ 拖动移动"
            textSize = 12f
            alpha = 0.8f
            setPadding((d * 10).toInt(), 0, 0, 0)
        }
        val alphaBtn = Button(context).apply {
            text = "◐"
            textSize = 12f
            setOnClickListener {
                alphaLevel = when (alphaLevel) {
                    1.0f -> 0.75f
                    0.75f -> 0.5f
                    else -> 1.0f
                }
                box.alpha = alphaLevel
            }
        }
        val minBtn = Button(context).apply {
            text = "—"
            textSize = 12f
            setOnClickListener { setMinimized(true) }
        }
        val restoreBtn = Button(context).apply {
            text = "还原"
            textSize = 12f
            setOnClickListener { onRestore?.invoke() }
        }
        grabber.addView(handleLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        grabber.addView(alphaBtn, LinearLayout.LayoutParams(
            (d * 44).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        grabber.addView(minBtn, LinearLayout.LayoutParams(
            (d * 44).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        grabber.addView(restoreBtn, LinearLayout.LayoutParams(
            (d * 60).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        grabber.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, (d * 32).toInt()).apply {
            topMargin = (d * 4).toInt(); marginStart = (d * 4).toInt(); marginEnd = (d * 4).toInt()
        }

        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(grabber, LayoutParams(
            LayoutParams.MATCH_PARENT, (d * 32).toInt(), Gravity.TOP).apply {
            topMargin = (d * 4).toInt(); marginStart = (d * 4).toInt(); marginEnd = (d * 4).toInt()
        })

        // v1.5 M3E：容器整体大圆角 + 投影
        background = GradientDrawable().apply {
            cornerRadius = d * 24f
            setColor(Color.TRANSPARENT)
        }
        elevation = d * 12f

        box.pivotX = 0f
        box.pivotY = 0f
        enableDrag()
        post { centerHorizontally() }
    }

    /** 挂载键盘 View（自固定面板迁移） */
    fun attachKeyboard(v: View) {
        box.removeAllViews()
        box.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        post { centerHorizontally() }
    }

    /** 摘下键盘 View（还原到固定面板） */
    fun detachKeyboard(): View? {
        val v = box.getChildAt(0)
        if (v != null) box.removeView(v)
        return v
    }

    fun setTheme(theme: Theme) {
        grabber.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                d * 12f, d * 12f, d * 12f, d * 12f, 0f, 0f, 0f, 0f)
            setColor(theme.keyBgPressed)
        }
    }

    private fun setMinimized(min: Boolean) {
        minimized = min
        box.visibility = if (min) GONE else VISIBLE
        onMinimize?.invoke(min)
        if (!min) return
        // 最小化为条：点击手柄展开
        grabber.setOnClickListener { setMinimized(false) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        grabber.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = this.translationX; startY = this.translationY
                    dragging = false
                    grabber.setOnClickListener(null)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (dragging || kotlin.math.abs(dx) > d * 6 || kotlin.math.abs(dy) > d * 6) {
                        dragging = true
                        magnetize(startX + dx, startY + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    /** 边缘磁吸（§5.7）：距边 <24dp 吸附 */
    private fun magnetize(x: Float, y: Float) {
        if (width <= 0 || box.width <= 0) {
            translationX = x
            translationY = y
            return
        }
        val maxX = (width - box.width * scale).coerceAtLeast(0f)
        val maxY = (height - grabber.height - box.height * scale).coerceAtLeast(0f)
        val margin = d * 24f
        val nx = when {
            x < margin -> 0f
            x > maxX - margin -> maxX
            else -> x.coerceIn(0f, maxX)
        }
        translationX = nx
        translationY = y.coerceIn(0f, maxY)
    }

    fun centerHorizontally() {
        if (width <= 0) return
        val maxX = (width - box.width * scale).coerceAtLeast(0f)
        translationX = maxX / 2f
        translationY = 0f
    }

    /** 布局变化（横屏切换）自动回中（§5.7） */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != w && w > 0) post { centerHorizontally() }
    }
}
