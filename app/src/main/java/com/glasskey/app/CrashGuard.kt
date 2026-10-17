package com.glasskey.app

import android.content.Context
import java.io.File

/**
 * 崩溃守卫：全局未捕获异常落盘（内部存储，无权限需求）。
 * 下次 MainActivity 启动时检测到日志会提供"分享崩溃日志"入口。
 */
object CrashGuard {
    const val FILE_NAME = "crash_last.txt"

    fun crashFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                ctx.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { out ->
                    val sb = StringBuilder()
                    sb.append("线程: ").append(t.name).append('\n')
                    sb.append("版本: 1.5.1\n\n")
                    sb.append(android.util.Log.getStackTraceString(e))
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
                ?: Runtime.getRuntime().exit(2)
        }
    }

    /** 读取并清空上次崩溃日志（返回 null 表示无日志） */
    fun take(ctx: Context): String? {
        val f = crashFile(ctx)
        if (!f.exists()) return null
        return try {
            val s = f.readText(Charsets.UTF_8)
            f.delete()
            if (s.isBlank()) null else s
        } catch (_: Throwable) { null }
    }
}
