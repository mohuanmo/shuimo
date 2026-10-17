package com.glasskey.ime.dev

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5.8 开发者日志（环形缓冲）：开启后将输入法关键运行事件写入内存
 * 环形缓冲（500 行），开发者模式页面查看/复制。
 * 线程安全；关闭时零开销（直接返回）。
 */
object GkLogger {

    @Volatile
    var enabled: Boolean = false

    private const val CAP = 500
    private val buf = ArrayDeque<String>(CAP)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} $tag: $msg"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > CAP) buf.removeFirst()
        }
    }

    fun logThrowable(tag: String, msg: String, t: Throwable) {
        if (!enabled) return
        log(tag, "$msg · ${t.javaClass.simpleName}: ${t.message}")
    }

    fun dump(): String = synchronized(buf) { buf.joinToString("\n") }

    fun clear() = synchronized(buf) { buf.clear() }

    fun count(): Int = synchronized(buf) { buf.size }
}
