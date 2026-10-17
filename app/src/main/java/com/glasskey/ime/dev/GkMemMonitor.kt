package com.glasskey.ime.dev

import android.app.ActivityManager
import android.content.Context

/**
 * v1.6.0 内存观测：把 Java 堆用量与进程 PSS 写入开发者日志环形缓冲，
 * 供真机上核对"输入法弹出瞬时 / 持续运行 / 打字时"的内存占用。
 *
 * 采样点（均为零成本守卫：GkLogger.enabled=false 时直接跳过）：
 *  - onCreateInputView / onStartInputView（弹出瞬时）
 *  - commitText 每 100 次上屏（打字持续）
 *  - toggleFloating（悬浮窗开/关）
 *
 * @param tag 日志标签（即用户在开发者页看到的前缀）
 * @param javaOnly true 时跳过 PSS 读取（PSS 需要 ActivityManager 调用，
 *        有一定开销，只在低频采样点使用）
 */
object GkMemMonitor {

    /** Java 堆快照：used/max MB */
    fun heap(): Pair<Long, Long> {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
        val max = rt.maxMemory() / 1048576L
        return used to max
    }

    /** 进程 PSS（MB，Debug.MemoryInfo.totalPss 单位是 kB；v1.6.3 修复日志误标 227738MB） */
    fun pss(ctx: Context): Int = try {
        val mi = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(mi)
        (mi.totalPss / 1024).coerceAtLeast(0)
    } catch (_: Throwable) {
        -1
    }

    /** 记一条内存快照到开发者日志 */
    fun logSnapshot(tag: String, ctx: Context? = null) {
        if (!GkLogger.enabled) return
        val (used, max) = heap()
        val pss = ctx?.let { pss(it) } ?: -1
        GkLogger.log("mem", "$tag java=${used}MB/${max}MB pss=${pss}MB")
    }
}
