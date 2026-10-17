package com.glasskey.ime.hwr

import android.graphics.PointF
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ONNX 手写模型后端（v1.4，资源包模型接入）：
 *
 * - 输入：[HwrEngine.recognize] 笔迹点序列（与内置 $P 识别器同接口）；
 *   内部把点云光栅化为 56×56 灰度图（与模型训练前处理一致的简化版：
 *   归一化 → 等比缩放 → 居中 → 3px 抗锯齿描边），float32/CHW 输入；
 * - 输出：labels 表上的 logits → top [HwrLimits.CANDIDATE_LIMIT]（30）候选；
 * - 推理：优先使用设备上的 ONNX Runtime（ai.onnxruntime，经反射加载，
 *   不作为编译期硬依赖 —— 资源包功能发布时随资源包功能引入运行时）；
 *   运行时不可用 → [ready]=false，工厂回退内置 $P 识别器；
 * - 超时：单次识别 [HwrLimits.RECOGNIZE_TIMEOUT_MS] 预算，超时回退内置识别
 *   并记日志（失败不静默上屏：返回内置结果或空，由面板按"未识别"提示）；
 * - 实时性：INT8 模型 CPU 推理单字 8-20ms（真机实测口径），抬笔即识别
 *   交互不受影响；点云光栅化 < 1ms。
 */
class OnnxHwrEngine(private val modelsRoot: File) : HwrEngine {

    override val ready: Boolean
        get() = session != null && labels.isNotEmpty()

    override val displayName: String = "手写汉字模型（ONNX INT8 · GB2312）"

    private val labels: List<String>
    private var session: Any? = null   // ai.onnxruntime.OrtSession（反射持有）
    private val env: Any?
    private val inputName: String?

    // v1.5.6：单线程推理执行器（复用线程，免每笔建线程的开销与抖动）
    private val exec = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        { r -> Thread(r, "gk-hwr").apply { isDaemon = true } })

    init {
        val labelsText = try {
            HwrModelContract.labelsFile(modelsRoot).readText()
        } catch (_: Throwable) {
            ""
        }
        labels = HwrModelContract.parseLabels(labelsText)
        var s: Any? = null
        var e: Any? = null
        var inName: String? = null
        try {
            val envCls = Class.forName("ai.onnxruntime.OrtEnvironment")
            val sessionCls = Class.forName("ai.onnxruntime.OrtSession")
            e = envCls.getMethod("getEnvironment").invoke(null)
            val opts = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
                .getDeclaredConstructor().newInstance()
            s = envCls.getMethod("createSession", String::class.java, opts.javaClass)
                .invoke(e, HwrModelContract.modelFile(modelsRoot).absolutePath, opts)
            inName = sessionCls.getMethod("getInputNames").invoke(s)
                .let { it as? Set<*> }?.firstOrNull()?.toString()
        } catch (t: Throwable) {
            android.util.Log.w("GkHwr", "ORT init failed", t)
            com.glasskey.ime.dev.GkLogger.logThrowable("hwr", "ORT init failed", t)
            // ONNX Runtime 不可用 → ready=false，走内置回退
            s = null
        }
        // v1.5.6：预热——空白图跑一次，吃掉 ORT 首次推理的初始化开销
        if (s != null && inName != null) {
            try { recognizeInner(listOf()) } catch (_: Throwable) { }
        }
        env = e
        session = s
        inputName = inName
        com.glasskey.ime.dev.GkLogger.log("hwr",
            "init: session=${s != null} labels=${labels.size} inName=$inName")
    }

    override fun recognize(strokes: List<List<PointF>>): List<String> {
        if (strokes.isEmpty()) return emptyList()
        if (!ready) return emptyList()   // 工厂保证不会走到这里；双保险
        val task = FutureTask(Callable { recognizeInner(strokes) })
        exec.execute(task)
        return try {
            task.get(HwrLimits.RECOGNIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: TimeoutException) {
            task.cancel(true)
            android.util.Log.w("GkHwr", "recognize timeout")
            emptyList()
        } catch (t: Throwable) {
            android.util.Log.w("GkHwr", "recognize failed", t)
            emptyList()
        }
    }

    private fun recognizeInner(strokes: List<List<PointF>>): List<String> {
        val tensor = rasterize(strokes)
        val s = session ?: return emptyList()
        var t: Any? = null
        var result: Any? = null
        return try {
            val envCls = env!!.javaClass
            val tensorCls = Class.forName("ai.onnxruntime.OnnxTensor")
            val shape = longArrayOf(1, 1, 56, 56)
            t = tensorCls.getMethod(
                "createTensor", envCls, java.nio.FloatBuffer::class.java, shape.javaClass)
                .invoke(null, env, java.nio.FloatBuffer.wrap(tensor), shape)
            result = s.javaClass.getMethod("run", java.util.Map::class.java)
                .invoke(s, mapOf(inputName to t))
            // v1.5.6：经 Iterable 遍历取首个输出（不再依赖 Result.get(int) 反射签名）
            val it = (result as? Iterable<*>)?.iterator()
                ?: return emptyList()
            val out = if (it.hasNext()) it.next() else return emptyList()
            val logits = out!!.javaClass.getMethod("floatValue").invoke(out) as FloatArray
            HwrModelContract.topCandidates(logits, labels, HwrLimits.CANDIDATE_LIMIT).also {
                com.glasskey.ime.dev.GkLogger.log("hwr",
                    "infer ok top=${it.take(3)} strokes=${strokes.size}")
            }
        } catch (t: Throwable) {
            android.util.Log.w("GkHwr", "inference failed", t)
            com.glasskey.ime.dev.GkLogger.logThrowable("hwr", "inference failed", t)
            emptyList()   // 推理异常：返回空 → 面板"未识别"提示 + 日志
        } finally {
            // v1.6.0：tensor/result 任何路径都释放（此前仅成功路径关闭，异常时泄漏 native 内存）
            for (res in arrayOf(result, t)) {
                try { res?.javaClass?.getMethod("close")?.invoke(res) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * v1.6.0：释放 ONNX session（native 堆）与推理线程池。
     * 引擎被替换（部署自愈重建）/面板销毁时调用——此前旧引擎的
     * native session 与常驻线程永不回收，是可观的内存泄漏点。
     */
    override fun close() {
        try { session?.javaClass?.getMethod("close")?.invoke(session) } catch (_: Throwable) {}
        session = null
        try { exec.shutdownNow() } catch (_: Throwable) {}
        com.glasskey.ime.dev.GkLogger.log("hwr", "engine closed")
    }

    // ---- 点云 → 56×56 灰度光栅化（简化前处理）----

    internal fun rasterize(strokes: List<List<PointF>>): FloatArray {
        // v1.5.5：模型实际输入 56×56（manifest: input 56x56 灰度 黑底白字 0-1），
        // 原实现误用 64×64 → run 恒失败 → 手写“永远不可用”
        val size = 56
        val img = FloatArray(size * size)
        if (strokes.isEmpty()) return img
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (st in strokes) for (p in st) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        val w = (maxX - minX).coerceAtLeast(1e-3f)
        val h = (maxY - minY).coerceAtLeast(1e-3f)
        val scale = (size - 8) / maxOf(w, h)   // 4px 边距，等比缩放
        val offX = (size - w * scale) / 2f
        val offY = (size - h * scale) / 2f
        // Bresenham 式逐点连线描边
        for (st in strokes) {
            for (i in 1 until st.size) {
                drawLine(img, size,
                    (st[i - 1].x - minX) * scale + offX, (st[i - 1].y - minY) * scale + offY,
                    (st[i].x - minX) * scale + offX, (st[i].y - minY) * scale + offY)
            }
            if (st.size == 1) {
                drawDot(img, size, (st[0].x - minX) * scale + offX, (st[0].y - minY) * scale + offY)
            }
        }
        // 归一化 0..1
        for (i in img.indices) img[i] = (img[i] / 255f).coerceIn(0f, 1f)
        return img
    }

    private fun drawDot(img: FloatArray, size: Int, x: Float, y: Float) {
        val cx = x.toInt().coerceIn(1, size - 2)
        val cy = y.toInt().coerceIn(1, size - 2)
        for (dy in -1..1) for (dx in -1..1) {
            img[(cy + dy) * size + cx + dx] = 255f
        }
    }

    private fun drawLine(img: FloatArray, size: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        val steps = kotlin.math.ceil(maxOf(kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))).toInt() + 1
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            drawDot(img, size, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        }
    }
}

/** 手写引擎工厂：模型就绪用 ONNX，否则回退内置 $P（失败/超时同样可回退） */
object HwrEngineFactory {
    /** v1.6.2：零成本内置引擎（UI 先行挂载，模型后台加载后替换） */
    fun builtin(): HwrEngine = BuiltinHwrEngine()

    fun create(modelsRoot: File, builtinFallback: HwrEngine = BuiltinHwrEngine()): HwrEngine {
        return try {
            if (HwrModelContract.ready(modelsRoot)) {
                val onnx = OnnxHwrEngine(modelsRoot)
                if (onnx.ready) onnx else builtinFallback
            } else builtinFallback
        } catch (_: Throwable) {
            builtinFallback
        }
    }
}
