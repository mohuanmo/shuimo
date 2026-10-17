package com.glasskey.ime.floating

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 悬浮键盘路线 B 控制器（v1.3，§5.7）：SYSTEM_ALERT_WINDOW 真悬浮窗。
 *
 * 与路线 A（窗口内浮动容器）的本质差异：键盘视图挂到系统级
 * TYPE_APPLICATION_OVERLAY 窗口，可覆盖在任何应用之上；IME 主窗口
 * 仅保留占位（会话不中断），InputConnection 始终有效——
 * 关键窗口参数：
 *   FLAG_NOT_FOCUSABLE     不抢应用焦点 → 目标输入框焦点不动，编辑直达；
 *   FLAG_NOT_TOUCH_MODAL   窗口外触摸穿透给应用（其余区域正常交互）；
 *   FLAG_ALT_FOCUSABLE_IM  本窗口自身不再触发 IME 弹出（防主键盘窗口顶上来）。
 * 顶部拖动条：按住移动整窗；宽度 72% 屏宽，默认停靠右下。
 */
class FloatingOverlayController(
    private val context: Context,
    private val content: ViewGroup,  // mainColumn（候选栏 + 工具栏 + 键盘整体）
) {
    private var wm: WindowManager? = null
    private var overlayView: ViewGroup? = null
    private var params: WindowManager.LayoutParams? = null

    // show 时记录原宿主信息，hide 时精确还原视图树
    private var originalParent: ViewGroup? = null
    private var originalParams: ViewGroup.LayoutParams? = null
    private var originalIndex = 0

    val isShowing: Boolean get() = overlayView != null

    /** v1.5.4：悬浮窗根容器（语音 HUD 挂到键盘上方用）；非悬浮态为 null */
    var overlayRoot: ViewGroup? = null
        private set

    /** 摘出 content 挂悬浮窗；失败返回 false 并还原视图树（调用方降级路线 A） */
    fun show(): Boolean {
        if (overlayView != null) return true
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val parent = content.parent as? ViewGroup ?: return false
        val d = context.resources.displayMetrics.density

        originalParent = parent
        originalParams = content.layoutParams
        originalIndex = (parent as? FrameLayout)?.indexOfChild(content) ?: 0
        parent.removeView(content)
        content.visibility = View.VISIBLE

        val wrap = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        wrap.addView(content, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // v1.6.1 拖动手柄改「系统小胶囊」样式、移到悬浮键盘正下方——
        // 原 24dp 全宽透明条盖在键盘顶部，导致候选/工具栏点击被手柄吞掉
        //（真机反馈：悬浮模式候选点不到）。胶囊仅在键盘下缘 22dp 区域响应拖动。
        val handle = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(0x59808D99.toInt())
            }
        }
        wrap.addView(handle, android.widget.LinearLayout.LayoutParams(
            (d * 96).toInt(), (d * 14).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (d * 6).toInt()
            bottomMargin = (d * 4).toInt()
        })
        // 胶囊热区外扩（上下各 8dp 仍在手柄内，拖动更稳）
        handle.setPadding(0, (d * 8).toInt(), 0, (d * 8).toInt())
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    val lp2 = params ?: return@setOnTouchListener false
                    lastX = lp2.x; lastY = lp2.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp2 = params ?: return@setOnTouchListener false
                    lp2.x = lastX - (ev.rawX - lastRawX).toInt()  // gravity END：x 向左为正
                    lp2.y = lastY - (ev.rawY - lastRawY).toInt()  // gravity BOTTOM：y 向上为正
                    try { manager.updateViewLayout(wrap, lp2) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // v1.5.8：拖动结束持久化位置（下次悬浮直接回到此处）
                    val lp2 = params
                    if (lp2 != null) {
                        try {
                            com.glasskey.app.GkPrefs.setFloatingLastPos(context, lp2.x, lp2.y)
                        } catch (_: Throwable) {}
                    }
                    false
                }
                else -> false
            }
        }

        val lp = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels *
                (try { com.glasskey.app.GkPrefs.floatingWidthPct(context) } catch (_: Throwable) { 72 })
                / 100f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        // v1.5.8：恢复上次悬浮位置（无记录用默认停靠右下）
        val (savedX, savedY) = try { com.glasskey.app.GkPrefs.floatingLastPos(context) }
        catch (_: Throwable) { -1 to -1 }
        lp.x = if (savedX >= 0) savedX else (d * 8).toInt()
        lp.y = if (savedY >= 0) savedY else (d * 48).toInt()
        params = lp
        // v1.5.4：悬浮窗大圆角（32dp）+ 裁剪内容，键盘面板边角不再直角生硬
        try {
            wrap.clipToOutline = true
            wrap.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, d * 32f)
                }
            }
            wrap.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d * 32f
                setColor(0x00000000)
            }
        } catch (_: Throwable) { }
        try {
            manager.addView(wrap, lp)
        } catch (t: Throwable) {
            // addView 失败（权限中途被收回等）：content 还回原容器，报告失败
            restoreContent()
            return false
        }
        overlayView = wrap
        overlayRoot = wrap
        wm = manager
        return true
    }

    /** 收回悬浮窗，content 还回原宿主；返回是否成功 */
    fun hide(): Boolean {
        val wrap = overlayView ?: return true
        val manager = wm ?: return false
        try { manager.removeView(wrap) } catch (_: Throwable) {}
        overlayView = null
        overlayRoot = null
        wm = null
        params = null
        wrap.removeAllViews()
        restoreContent()
        return true
    }

    private fun restoreContent() {
        val parent = originalParent
        if (parent != null && content.parent == null) {
            val lp = originalParams ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            try {
                parent.addView(content, originalIndex.coerceAtMost(parent.childCount), lp)
            } catch (_: Throwable) {
                parent.addView(content, lp)
            }
        }
        originalParent = null
        originalParams = null
    }

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastX = 0
    private var lastY = 0
}
