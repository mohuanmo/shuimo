package com.glasskey.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinCapHelperTest {

    @Test
    fun `shift cycles off once lock`() {
        var m = LatinCapHelper.Mode.OFF
        m = LatinCapHelper.shift(m); assertEquals(LatinCapHelper.Mode.SHIFT_ONCE, m)
        m = LatinCapHelper.shift(m); assertEquals(LatinCapHelper.Mode.LOCK, m)
        m = LatinCapHelper.shift(m); assertEquals(LatinCapHelper.Mode.OFF, m)
    }

    @Test
    fun `once mode reverts after a letter`() {
        val m = LatinCapHelper.afterLetter(LatinCapHelper.Mode.SHIFT_ONCE)
        assertEquals(LatinCapHelper.Mode.OFF, m)
        // LOCK 不因单字母退出
        val l = LatinCapHelper.afterLetter(LatinCapHelper.Mode.LOCK)
        assertEquals(LatinCapHelper.Mode.LOCK, l)
    }

    @Test
    fun `letter case follows mode`() {
        assertTrue(LatinCapHelper.letterCase(LatinCapHelper.Mode.SHIFT_ONCE))
        assertTrue(LatinCapHelper.letterCase(LatinCapHelper.Mode.LOCK))
        assertFalse(LatinCapHelper.letterCase(LatinCapHelper.Mode.OFF))
    }

    @Test
    fun `auto cap after sentence end`() {
        assertTrue(LatinCapHelper.autoCapNext(""))
        assertTrue(LatinCapHelper.autoCapNext("hello. "))
        assertTrue(LatinCapHelper.autoCapNext("what?"))
        assertFalse(LatinCapHelper.autoCapNext("hi "))
        assertFalse(LatinCapHelper.autoCapNext("a,b"))
    }

    @Test
    fun `apply upper lower`() {
        assertEquals('A', LatinCapHelper.apply('a', true))
        assertEquals('a', LatinCapHelper.apply('a', false))
    }
}
