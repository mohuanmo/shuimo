package com.glasskey.ime.ui

/**
 * 键盘键位定义（§5.1.2 三布局：拼音 26 键 / T9 / 拉丁）
 * 纯数据 + 纯工厂函数，可脱离 Android 环境单元测试。
 */
data class KeyDef(
    val label: String,
    val code: Int,
    val weight: Float = 1f,
    val isFunction: Boolean = false,
)

data class RowDef(val keys: List<KeyDef>)

data class KeyboardDef(val rows: List<RowDef>) {
    val codeSet: Set<Int> get() = rows.flatMap { r -> r.keys.map { it.code } }.toSet()
}

/** 返回替换指定 code 键标签后的布局副本（中/英键动态显示当前语言，§5.1.3） */
fun KeyboardDef.withKeyLabel(code: Int, label: String): KeyboardDef =
    copy(rows = rows.map { row ->
        RowDef(row.keys.map { if (it.code == code) it.copy(label = label) else it })
    })

/**
 * v1.7.1：数字小键盘（电脑 NumPad 风格）——独立布局 + 键盘上的「123」
 * 切换键进出（与中/英切换同范式的按键切换，不占字母区顶行）。
 */
fun numpad(): KeyboardDef = KeyboardDef(
    listOf(
        RowDef(listOf(
            KeyDef("7", '7'.code), KeyDef("8", '8'.code), KeyDef("9", '9'.code),
        )),
        RowDef(listOf(
            KeyDef("4", '4'.code), KeyDef("5", '5'.code), KeyDef("6", '6'.code),
        )),
        RowDef(listOf(
            KeyDef("1", '1'.code), KeyDef("2", '2'.code), KeyDef("3", '3'.code),
        )),
        RowDef(listOf(
            KeyDef("返回", KeyboardDefs.CODE_NUMPAD, weight = 1.4f, isFunction = true),
            KeyDef("0", '0'.code),
            KeyDef(".", KeyboardDefs.CODE_SYMBOL_PERIOD),
            KeyDef("⌫", KeyboardDefs.CODE_BACKSPACE, weight = 1.4f, isFunction = true),
        )),
    )
)

object KeyboardDefs {

    // 特殊键码（负值，不与字符冲突）
    const val CODE_SHIFT = -1
    const val CODE_BACKSPACE = -2
    const val CODE_LAYOUT_SWITCH = -3   // 中/英切换
    const val CODE_MODE_SWITCH = -4     // 26键/T9切换
    const val CODE_ENTER = -5
    const val CODE_NUMPAD = -6          // 数字小键盘切换（v1.7.1）
    const val CODE_SYMBOL_COMMA = ','.toInt()
    const val CODE_SYMBOL_PERIOD = '.'.toInt()
    const val CODE_SEP_APOSTROPHE = '\''.toInt()  // 拼音歧义切分分隔符（§4.2.1）

    /** 拼音 26 键（§5.1.2）：QWERTY + 分隔符 + 功能行 */
    fun pinyin26(): KeyboardDef = KeyboardDef(
        listOf(
            RowDef("qwertyuiop".map { KeyDef(it.toString(), it.code) }),
            RowDef("asdfghjkl".map { KeyDef(it.toString(), it.code) }),
            RowDef(
                "zxcvbnm".map { KeyDef(it.toString(), it.code) } +
                    KeyDef("'", CODE_SEP_APOSTROPHE, weight = 1f)
            ),
            RowDef(
                listOf(
                    KeyDef("中/英", CODE_LAYOUT_SWITCH, weight = 1.6f, isFunction = true),
                    KeyDef("T9", CODE_MODE_SWITCH, weight = 1.2f, isFunction = true),
                    KeyDef("123", CODE_NUMPAD, weight = 1f, isFunction = true),
                    KeyDef(",", CODE_SYMBOL_COMMA, weight = 1f),
                    // v1.5.7：中文 26 键补回车键（与英文布局同位置：底行右端），
                    // 支持长按上滑清空光标前文本（KeyboardView onEnterSwipeClear）
                    KeyDef("空格", ' '.code, weight = 2.8f),
                    KeyDef(".", CODE_SYMBOL_PERIOD, weight = 1f),
                    KeyDef("⏎", CODE_ENTER, weight = 1.6f, isFunction = true),
                    KeyDef("⌫", CODE_BACKSPACE, weight = 1.6f, isFunction = true),
                )
            ),
        )
    )

    /** T9 九宫格（§4.2.2）：数字 → 音节数字模式 */
    fun t9(): KeyboardDef = KeyboardDef(
        listOf(
            RowDef(
                listOf(
                    KeyDef("1", '1'.code),
                    KeyDef("2\nabc", '2'.code),
                    KeyDef("3\ndef", '3'.code),
                )
            ),
            RowDef(
                listOf(
                    KeyDef("4\nghi", '4'.code),
                    KeyDef("5\njkl", '5'.code),
                    KeyDef("6\nmno", '6'.code),
                )
            ),
            RowDef(
                listOf(
                    KeyDef("7\npqrs", '7'.code),
                    KeyDef("8\ntuv", '8'.code),
                    KeyDef("9\nwxyz", '9'.code),
                )
            ),
            RowDef(
                listOf(
                    KeyDef("中/英", CODE_LAYOUT_SWITCH, weight = 1.3f, isFunction = true),
                    KeyDef("26", CODE_MODE_SWITCH, weight = 0.9f, isFunction = true),
                    KeyDef("123", CODE_NUMPAD, weight = 0.9f, isFunction = true),
                    // v1.5.6：九键补逗号/句号键（中文模式下自动上"，”/“。"）
                    KeyDef(",", CODE_SYMBOL_COMMA, weight = 0.9f),
                    KeyDef("空格", ' '.code, weight = 1.8f),
                    KeyDef(".", CODE_SYMBOL_PERIOD, weight = 0.9f),
                    // v1.5.7：九键补回车键（与 26 键一致）
                    KeyDef("⏎", CODE_ENTER, weight = 1.2f, isFunction = true),
                    KeyDef("⌫", CODE_BACKSPACE, weight = 1.4f, isFunction = true),
                )
            ),
        )
    )

    /** 拉丁全键盘（§4.4）：QWERTY + Shift + 退格 + 回车 */
    fun latin(upper: Boolean, modeLabel: String = "中"): KeyboardDef {
        fun map(s: String) = s.map {
            KeyDef(if (upper) it.uppercaseChar().toString() else it.toString(), it.code)
        }
        return KeyboardDef(
            listOf(
                RowDef(map("qwertyuiop")),
                RowDef(map("asdfghjkl")),
                RowDef(
                    listOf(
                        KeyDef("⇧", CODE_SHIFT, weight = 1.5f, isFunction = true),
                    ) + map("zxcvbnm") + listOf(
                        KeyDef("⌫", CODE_BACKSPACE, weight = 1.5f, isFunction = true),
                    )
                ),
                RowDef(
                    listOf(
                        KeyDef(modeLabel, CODE_LAYOUT_SWITCH, weight = 1.6f, isFunction = true),
                        KeyDef("123", CODE_NUMPAD, weight = 1.2f, isFunction = true),
                        KeyDef(",", CODE_SYMBOL_COMMA, weight = 1f),
                        KeyDef("空格", ' '.code, weight = 3.4f),
                        KeyDef(".", CODE_SYMBOL_PERIOD, weight = 1f),
                        KeyDef("⏎", CODE_ENTER, weight = 1.8f, isFunction = true),
                    )
                ),
            )
        )
    }

    /** 符号数字补充行（候选条左侧滑出/长按空格切换，M1 常驻） */
    fun symbols(): List<Char> =
        "1234567890-/:;()¥@&\"。，！？".toList()

    /**
     * v1.5.8：26 键字母键上滑 → 标点映射（Gboard 风格，设置可开关）。
     * 上排偏中文标点，下排偏符号，键帽右上角绘制小字提示。
     */
    val SWIPE_PUNCT: Map<Char, Char> = mapOf(
        'q' to '！', 'w' to '？', 'e' to '、', 'r' to '～', 't' to '…',
        'y' to '《', 'u' to '》', 'i' to '「', 'o' to '」', 'p' to '；',
        'a' to '@', 's' to '#', 'd' to '¥', 'f' to '%', 'g' to '&',
        'h' to '-', 'j' to '+', 'k' to '=', 'l' to '/',
        'z' to '（', 'x' to '）', 'c' to '：', 'v' to '"', 'b' to '\'',
        'n' to '“', 'm' to '”',
    )
}
