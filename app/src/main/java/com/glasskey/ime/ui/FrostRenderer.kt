package com.glasskey.ime.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 磨砂玻璃渲染器（§6.2）：
 * - 面板：半透明底色 + 顶部高光渐变 + 1px 内描边（玻璃厚度感）
 * - 按键：竖向微渐变（上亮下暗）+ 圆角 + 按下态加深
 * API 26+ 无依赖实现；跨窗口真模糊（RenderEffect/blur behind）因各系统差异留待 M2。
 */
object FrostRenderer {

    /** 键盘面板底：半透明底色 + 顶部高光 */
    fun drawPanel(c: Canvas, bounds: RectF, theme: Theme, radius: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.panelBg }
        c.drawRoundRect(bounds, radius, radius, p)
        // 顶部高光（玻璃受光）
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, bounds.top, 0f, bounds.top + bounds.height() * 0.35f,
                Color.argb(38, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(bounds, radius, radius, glow)
        // 内描边
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = theme.divider
        }
        c.drawRoundRect(bounds, radius, radius, stroke)
    }

    /** 单个按键：微渐变圆角矩形 */
    fun drawKey(c: Canvas, bounds: RectF, theme: Theme, pressed: Boolean, radius: Float) {
        val base = if (pressed) theme.keyBgPressed else theme.keyBg
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, bounds.top, 0f, bounds.bottom,
            lighten(base, if (pressed) 1.0f else 1.06f),
            darken(base, if (pressed) 0.94f else 1.0f),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(bounds, radius, radius, p)
        if (!pressed) {
            val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.argb(30, 255, 255, 255)
            }
            c.drawRoundRect(bounds, radius, radius, hi)
        }
    }

    fun lighten(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).coerceAtMost(255f).toInt(),
        (Color.green(color) * f).coerceAtMost(255f).toInt(),
        (Color.blue(color) * f).coerceAtMost(255f).toInt()
    )

    fun darken(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).toInt(),
        (Color.green(color) * f).toInt(),
        (Color.blue(color) * f).toInt()
    )
}
