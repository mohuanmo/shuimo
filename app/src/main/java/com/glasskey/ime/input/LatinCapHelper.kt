package com.glasskey.ime.input

/**
 * 拉丁大写状态机（§4.4.3）：
 * OFF → 单次 SHIFT（下一字母大写后回 OFF）→ LOCK（全大写）→ OFF
 * 句首自动大写：句末标点(.!?)+空格 后首字母触发单次大写。
 */
object LatinCapHelper {

    enum class Mode { OFF, SHIFT_ONCE, LOCK }

    /** 当前输入下应输出的字母大小写 */
    fun letterCase(mode: Mode): Boolean = mode != Mode.OFF

    /** 提交一个字母后推进状态 */
    fun afterLetter(mode: Mode): Mode =
        if (mode == Mode.SHIFT_ONCE) Mode.OFF else mode

    /** Shift 键循环：OFF → SHIFT_ONCE → LOCK → OFF */
    fun shift(mode: Mode): Mode = when (mode) {
        Mode.OFF -> Mode.SHIFT_ONCE
        Mode.SHIFT_ONCE -> Mode.LOCK
        Mode.LOCK -> Mode.OFF
    }

    /** 句首自动大写判定：前文以 . ! ? 结尾（含空格尾随） */
    fun autoCapNext(before: String): Boolean {
        val t = before.trimEnd()
        if (t.isEmpty()) return true  // 空编辑器首字母大写（常规文本场景）
        return t.last() in ".!?"
    }

    /** 应用大小写到待输出字母 */
    fun apply(ch: Char, upper: Boolean): Char =
        if (upper) ch.uppercaseChar() else ch
}
