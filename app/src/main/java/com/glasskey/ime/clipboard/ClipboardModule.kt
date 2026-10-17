package com.glasskey.ime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板模块（§5.8）：捕获 + 快捷动作识别。
 * - 键盘窗口获焦时读取 ClipboardManager（无需权限声明，Android 10+ 焦点机制天然满足）；
 * - EXTRA_IS_SENSITIVE 条目不入库（§S-04）；
 * - 快捷动作：链接 / 电话 / 6 位验证码直接识别，验证码可一键上屏。
 */
object ClipboardModule {

    /** 快捷动作类型（§5.8 快捷动作） */
    enum class Kind { NONE, OTP, URL, PHONE }

    data class SmartClip(val kind: Kind, val token: String, val label: String)

    private val OTP_RE = Regex("(?<![0-9A-Za-z])[0-9]{4,8}(?![0-9A-Za-z])")
    private val URL_RE = Regex("https?://[\\w.-]+(?:/[\\w./?%&=-]*)?")
    private val PHONE_RE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")

    /** 读取当前剪贴板内容；null = 无内容 / 读取失败 */
    fun readPrimary(ctx: Context): Pair<String, Boolean>? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
        if (text.isEmpty()) return null
        val sensitive = (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")
            ?: false)
        return text to sensitive
    }

    /** 快捷识别：验证码优先（一键上屏价值最高），其次链接、手机号 */
    fun smartClip(text: String): SmartClip? {
        PHONE_RE.find(text)?.let {
            return SmartClip(Kind.PHONE, it.value, "电话 ${it.value}")
        }
        URL_RE.find(text)?.let {
            return SmartClip(Kind.URL, it.value, it.value)
        }
        // OTP：文本较短且含独立 4-8 位数字段（避免长正文误判）
        if (text.length <= 200) {
            OTP_RE.find(text)?.let {
                if (it.value.length in 4..8) {
                    return SmartClip(Kind.OTP, it.value, "验证码 ${it.value}")
                }
            }
        }
        return null
    }

    /** 写入剪贴板（复制动作） */
    fun write(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
    }
}
