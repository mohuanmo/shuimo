package com.glasskey.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardDefsTest {

    @Test
    fun `pinyin26 has 26 letters plus apostrophe`() {
        val kb = KeyboardDefs.pinyin26()
        assertEquals(4, kb.rows.size)
        val codes = kb.codeSet
        for (c in 'a'..'z') {
            assertTrue("missing ${c}", c.code in codes)
        }
        assertTrue(KeyboardDefs.CODE_SEP_APOSTROPHE in codes)
        assertTrue(KeyboardDefs.CODE_BACKSPACE in codes)
        assertTrue(KeyboardDefs.CODE_LAYOUT_SWITCH in codes)
        assertTrue(KeyboardDefs.CODE_MODE_SWITCH in codes)
    }

    @Test
    fun `t9 covers digits 2 to 9`() {
        val kb = KeyboardDefs.t9()
        assertEquals(4, kb.rows.size)
        val codes = kb.codeSet
        for (c in '2'..'9') {
            assertTrue("missing ${c}", c.code in codes)
        }
    }

    @Test
    fun `latin has shift enter and all letters`() {
        val kb = KeyboardDefs.latin(upper = false)
        assertEquals(4, kb.rows.size)
        val codes = kb.codeSet
        for (c in 'a'..'z') {
            assertTrue("missing ${c}", c.code in codes)
        }
        assertTrue(KeyboardDefs.CODE_SHIFT in codes)
        assertTrue(KeyboardDefs.CODE_ENTER in codes)
        assertTrue(KeyboardDefs.CODE_BACKSPACE in codes)
    }

    @Test
    fun `weights are positive`() {
        for (kb in listOf(KeyboardDefs.pinyin26(), KeyboardDefs.t9(), KeyboardDefs.latin(false))) {
            for (row in kb.rows) {
                assertTrue(row.keys.sumOf { it.weight.toDouble() } > 0.0)
                for (k in row.keys) assertTrue(k.weight > 0f)
            }
        }
    }
}
