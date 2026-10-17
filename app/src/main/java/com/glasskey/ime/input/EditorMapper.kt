package com.glasskey.ime.input

import android.view.inputmethod.EditorInfo

/**
 * EditorInfo → 键盘布局映射（§5.1.3）
 * 纯静态逻辑，便于单元测试。
 */
object EditorMapper {
    /** 目标布局：拼音 26 键 / T9 / 拉丁 */
    const val LAYOUT_PINYIN = 0
    const val LAYOUT_LATIN = 1
    const val LAYOUT_NUMBER = 2

    fun map(editorInfo: EditorInfo?, fallback: Int = LAYOUT_PINYIN): Int {
        val it = editorInfo?.inputType ?: EditorInfo.TYPE_NULL
        if (it == EditorInfo.TYPE_NULL) return fallback
        return when (it and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_NUMBER -> LAYOUT_NUMBER
            EditorInfo.TYPE_CLASS_TEXT -> {
                val variant = it and EditorInfo.TYPE_MASK_VARIATION
                if (variant == EditorInfo.TYPE_TEXT_VARIATION_URI ||
                    variant == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variant == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variant == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                ) LAYOUT_LATIN else fallback
            }
            else -> fallback
        }
    }

    /** 回车动作标签（用于回车键显示） */
    fun enterAction(editorInfo: EditorInfo?): Int =
        if (editorInfo != null && editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0)
            editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        else EditorInfo.IME_ACTION_NONE
}
