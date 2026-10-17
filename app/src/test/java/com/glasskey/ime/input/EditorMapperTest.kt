package com.glasskey.ime.input

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorMapperTest {

    private fun info(type: Int): EditorInfo = EditorInfo().apply { inputType = type }

    @Test
    fun `null input falls back`() {
        assertEquals(EditorMapper.LAYOUT_PINYIN, EditorMapper.map(null))
        assertEquals(
            EditorMapper.LAYOUT_LATIN,
            EditorMapper.map(info(EditorInfo.TYPE_NULL), EditorMapper.LAYOUT_LATIN)
        )
    }

    @Test
    fun `number class maps to number`() {
        assertEquals(
            EditorMapper.LAYOUT_NUMBER,
            EditorMapper.map(info(EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_NORMAL))
        )
    }

    @Test
    fun `plain text uses fallback pinyin`() {
        assertEquals(
            EditorMapper.LAYOUT_PINYIN,
            EditorMapper.map(info(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL))
        )
    }

    @Test
    fun `email uri password map to latin`() {
        for (variant in intArrayOf(
            EditorInfo.TYPE_TEXT_VARIATION_URI,
            EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )) {
            assertEquals(
                "variant=$variant",
                EditorMapper.LAYOUT_LATIN,
                EditorMapper.map(info(EditorInfo.TYPE_CLASS_TEXT or variant))
            )
        }
    }

    @Test
    fun `enter action follows imeOptions`() {
        val e = info(EditorInfo.TYPE_CLASS_TEXT)
        e.imeOptions = EditorInfo.IME_ACTION_SEARCH
        assertEquals(EditorInfo.IME_ACTION_SEARCH, EditorMapper.enterAction(e))
        e.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_SEARCH
        assertEquals(EditorInfo.IME_ACTION_NONE, EditorMapper.enterAction(e))
    }
}
