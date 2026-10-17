package com.glasskey.ime.ui

import android.graphics.Color

/**
 * 水墨主题色板（v1.5，Material 3 Expressive 风格）：
 * 浅色 / 深色两套 + 5 款主题色（水墨意象），键盘底、按键、按下态、文字、高光。
 * 大圆角、高对比强调色、柔和分层底色 —— M3E「expressive shape & tonal」取向。
 */
data class Theme(
    val isDark: Boolean,
    val panelBg: Int,       // 键盘面板半透明底
    val keyBg: Int,         // 常态按键
    val keyBgPressed: Int,  // 按下态
    val keyText: Int,
    val accent: Int,        // 候选高亮 / 功能键
    val divider: Int,       // 分隔线/高光
    val candidateText: Int,
    val candidateHint: Int,
    val keyBgFn: Int,       // 功能键底（M3E secondary container，v1.5 深化）
) {
    companion object {
        val LIGHT = Theme(
            isDark = false,
            panelBg = Color.parseColor("#D9F2F4FA"),
            keyBg = Color.parseColor("#F2FFFFFF"),
            keyBgPressed = Color.parseColor("#FFDCE9F7"),
            keyText = Color.parseColor("#FF1B2430"),
            accent = Color.parseColor("#FF2E7CF6"),
            divider = Color.parseColor("#66FFFFFF"),
            candidateText = Color.parseColor("#FF16202B"),
            candidateHint = Color.parseColor("#99606B7A"),
            keyBgFn = Color.parseColor("#E6DCE9F7"),
        )
        val DARK = Theme(
            isDark = true,
            panelBg = Color.parseColor("#D8141A22"),
            keyBg = Color.parseColor("#F2293340"),
            keyBgPressed = Color.parseColor("#FF3D4B5C"),
            keyText = Color.parseColor("#FFE8EEF5"),
            accent = Color.parseColor("#FF6FA8FF"),
            divider = Color.parseColor("#44FFFFFF"),
            candidateText = Color.parseColor("#FFEDF2F8"),
            candidateHint = Color.parseColor("#99A8B4C2"),
            keyBgFn = Color.parseColor("#E6334152"),
        )

        /** 主题色（水墨意象，v1.5 设置界面可选）：0 墨蓝 1 黛青 2 绛紫 3 玄墨 4 朱砂 */
        val ACCENTS: List<Pair<String, Int>> = listOf(
            "墨蓝" to 0x2E7CF6,
            "黛青" to 0x00897B,
            "绛紫" to 0x8E5AC8,
            "玄墨" to 0x546E7A,
            "朱砂" to 0xE5533C,
        )

        fun accentLight(idx: Int): Int =
            0xFF000000.toInt() or ACCENTS[idx.coerceIn(ACCENTS.indices)].second

        /** 深色场景提亮（保证 M3E 对比度） */
        fun accentDark(idx: Int): Int {
            val c = accentLight(idx)
            fun lift(v: Int) = (v + 46).coerceAtMost(255)
            return Color.argb(0xFF, lift(Color.red(c)), lift(Color.green(c)), lift(Color.blue(c)))
        }

        fun of(isDark: Boolean): Theme = of(isDark, 0)

        fun of(isDark: Boolean, accentIndex: Int): Theme {
            val base = if (isDark) DARK else LIGHT
            if (accentIndex == 0) return base
            return base.copy(
                accent = if (isDark) accentDark(accentIndex) else accentLight(accentIndex),
                keyBgPressed = tintPressed(base.keyBgPressed, accentLight(accentIndex)),
            )
        }

        /** 按下态向主题色轻染（M3E tonal feedback） */
        private fun tintPressed(base: Int, accent: Int): Int {
            val t = 0.18f
            return Color.argb(
                Color.alpha(base),
                (Color.red(base) * (1 - t) + Color.red(accent) * t).toInt(),
                (Color.green(base) * (1 - t) + Color.green(accent) * t).toInt(),
                (Color.blue(base) * (1 - t) + Color.blue(accent) * t).toInt(),
            )
        }
    }
}
