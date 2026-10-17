package com.glasskey.ime.hwr

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 手写识别后端接口（§5.5，可插拔 IHwrEngine）：
 * - 内置实现 [BuiltinHwrEngine]：$P 点云识别器（Vatavu et al. 2012，免训练、免模型文件），
 *   覆盖数字 0-9 与简单汉字（一/二/三/十/人/八/七/九/口）—— 记账/验证码高频场景；
 * - 完整汉字模型（§5.5 INT8 CNN，GB2312 一二级）走资源包下发（§5.11 / §7.4），
 *   通过同一接口替换内置实现（OnnxHwrEngine，随资源包功能发布）。
 *
 * 现状（v1.7.4）：ONNX 汉字模型因训练阶段问题始终无法正常加载，手写功能整体不可用；
 * 内置 $P 点云仅覆盖数字与少量极简汉字，属于占位实现，不应视为可用的手写输入。
 */
interface HwrEngine {
    val ready: Boolean
    val displayName: String

    /** @return top-k 候选字，按置信度降序 */
    fun recognize(strokes: List<List<PointF>>): List<String>

    /** v1.6.0：释放引擎资源（ONNX session / 线程池）。替换或销毁前调用。 */
    fun close() {}
}

/** $P 点云识别器（内置，§5.5 "识别后端接口化、优先采用可再分发社区方案"） */
class BuiltinHwrEngine : HwrEngine {

    override val ready: Boolean get() = true
    override val displayName: String = "内置点云识别（数字·常用字）"

    /** $P 重采样点数（Vatavu et al. 2012 推荐 32） */
    private val N = 32

    /** 模板：字符 → 归一化点云列表（一个字可有多变体） */
    private val templates: Map<String, List<List<List<PointF>>>> = buildTemplates()

    override fun recognize(strokes: List<List<PointF>>): List<String> {
        if (strokes.isEmpty()) return emptyList()
        val probe = normalize(strokes)
        // 分数 = 与模板的最小 greedy 匹配距离；跨字取 top-k
        val scored = templates.entries.mapNotNull { (ch, variants) ->
            var best = Double.MAX_VALUE
            for (v in variants) {
                val d = greedyMatch(probe, v)
                if (d < best) best = d
            }
            if (best == Double.MAX_VALUE) null else ch to best
        }.sortedBy { it.second }
        return scored.take(5).map { it.first }
    }

    // ---- $P 核心：重采样 → 缩放平移归一 → greedy 匹配距离 ----

    private fun normalize(strokes: List<List<PointF>>): List<List<PointF>> {
        val pts = strokes.map { resample(it, N) }
        val all = pts.flatten()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in all) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        val size = maxOf(maxX - minX, maxY - minY, 1e-3f)
        return pts.map { s ->
            s.map { p ->
                PointF((p.x - minX) / size, (p.y - minY) / size)
            }
        }
    }

    private fun resample(stroke: List<PointF>, n: Int): List<PointF> {
        if (stroke.size < 2) {
            val p = stroke.firstOrNull() ?: return emptyList()
            return List(n) { PointF(p.x, p.y) }
        }
        var total = 0.0
        for (i in 1 until stroke.size) {
            total += dist(stroke[i - 1], stroke[i])
        }
        if (total < 1e-6) return List(n) { PointF(stroke[0].x, stroke[0].y) }
        val interval = total / (n - 1)
        val out = mutableListOf(stroke[0])
        var acc = 0.0
        var prev = stroke[0]
        var idx = 1
        while (out.size < n && idx < stroke.size) {
            val cur = stroke[idx]
            val d = dist(prev, cur)
            if (acc + d >= interval && d > 0) {
                val t = ((interval - acc) / d).toFloat()
                val np = PointF(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t)
                out.add(np)
                prev = np
                acc = 0.0
            } else {
                acc += d
                prev = cur
                idx++
            }
        }
        while (out.size < n) out.add(stroke.last())
        return out
    }

    /** $P greedy-merge：双向最近点距离均值（笔画序列无关，连笔友好） */
    private fun greedyMatch(a: List<List<PointF>>, b: List<List<PointF>>): Double {
        val pa = a.flatten()
        val pb = b.flatten()
        var sum = 0.0
        for (p in pa) sum += minDist(p, pb)
        for (p in pb) sum += minDist(p, pa)
        return sum / (pa.size + pb.size)
    }

    private fun minDist(p: PointF, set: List<PointF>): Double {
        var best = Double.MAX_VALUE
        for (q in set) {
            val d = dist(p, q)
            if (d < best) best = d
        }
        return best
    }

    private fun dist(a: PointF, b: PointF): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    // ---- 模板定义（归一化坐标 0..1，笔画 = 点序列）----

    private fun pts(vararg xy: Float): List<PointF> =
        (0 until xy.size step 2).map { PointF(xy[it], xy[it + 1]) }

    /** 圆弧采样 */
    private fun arc(cx: Float, cy: Float, r: Float, fromDeg: Double, toDeg: Double, n: Int = 16): List<PointF> {
        val out = mutableListOf<PointF>()
        for (i in 0 until n) {
            val t = fromDeg + (toDeg - fromDeg) * i / (n - 1)
            out.add(PointF((cx + r * cos(t / 180 * PI)).toFloat(), (cy + r * sin(t / 180 * PI)).toFloat()))
        }
        return out
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, n: Int = 12): List<PointF> {
        val out = mutableListOf<PointF>()
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            out.add(PointF(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
        }
        return out
    }

    private fun buildTemplates(): Map<String, List<List<List<PointF>>>> {
        val t = LinkedHashMap<String, List<List<List<PointF>>>>()
        t["0"] = listOf(listOf(arc(0.5f, 0.5f, 0.4f, -90.0, 270.0, 24)))
        t["1"] = listOf(
            listOf(line(0.5f, 0.15f, 0.5f, 0.85f)),
            listOf(line(0.35f, 0.25f, 0.52f, 0.15f, 6) + line(0.52f, 0.15f, 0.5f, 0.85f))
        )
        t["2"] = listOf(
            listOf(
                arc(0.5f, 0.32f, 0.3f, 180.0, 360.0 + 45.0, 18) +
                    line(0.72f, 0.53f, 0.2f, 0.78f, 10) +
                    line(0.2f, 0.78f, 0.8f, 0.78f)
            )
        )
        t["3"] = listOf(
            listOf(
                arc(0.42f, 0.32f, 0.24f, 150.0, -90.0, 14) +
                    arc(0.45f, 0.66f, 0.27f, 90.0, -150.0, 16)
            )
        )
        t["4"] = listOf(
            listOf(line(0.62f, 0.15f, 0.2f, 0.55f), line(0.12f, 0.55f, 0.85f, 0.55f),
                line(0.62f, 0.15f, 0.6f, 0.85f)),
            listOf(line(0.65f, 0.15f, 0.2f, 0.6f) + line(0.2f, 0.6f, 0.85f, 0.55f) +
                line(0.6f, 0.3f, 0.6f, 0.85f))
        )
        t["5"] = listOf(
            listOf(
                line(0.72f, 0.15f, 0.28f, 0.15f) + line(0.28f, 0.15f, 0.27f, 0.45f, 6) +
                    arc(0.52f, 0.62f, 0.26f, 180.0 + 90.0, 180.0 - 25.0, 18)
            )
        )
        t["6"] = listOf(
            listOf(
                arc(0.58f, 0.38f, 0.32f, 180.0 + 60.0, 180.0 - 45.0, 14) +
                    arc(0.5f, 0.64f, 0.22f, 180.0 + 90.0, 180.0 * 2 + 90.0 + 45.0, 16)
            )
        )
        t["7"] = listOf(
            listOf(line(0.15f, 0.2f, 0.85f, 0.2f) + line(0.85f, 0.2f, 0.4f, 0.85f, 14)),
            listOf(line(0.15f, 0.2f, 0.85f, 0.2f) + line(0.85f, 0.2f, 0.4f, 0.85f, 14) +
                line(0.42f, 0.5f, 0.68f, 0.5f, 6))
        )
        t["8"] = listOf(
            listOf(arc(0.5f, 0.3f, 0.2f, -90.0, 270.0, 14) +
                arc(0.5f, 0.7f, 0.26f, -90.0 - 180.0, -90.0 + 180.0, 16)),
            listOf(arc(0.5f, 0.68f, 0.26f, 90.0, 90.0 - 360.0, 16) +
                arc(0.5f, 0.3f, 0.2f, 90.0, 90.0 - 360.0, 14))
        )
        t["9"] = listOf(
            listOf(
                arc(0.48f, 0.32f, 0.22f, -90.0 + 360.0, -90.0, 18) +
                    line(0.7f, 0.32f, 0.62f, 0.85f, 10)
            )
        )
        // —— 简单汉字（单笔/两笔）——
        t["一"] = listOf(listOf(line(0.15f, 0.5f, 0.85f, 0.5f)))
        t["二"] = listOf(listOf(line(0.25f, 0.3f, 0.75f, 0.3f), line(0.15f, 0.7f, 0.85f, 0.7f)))
        t["三"] = listOf(listOf(
            line(0.28f, 0.22f, 0.72f, 0.22f),
            line(0.2f, 0.5f, 0.8f, 0.5f),
            line(0.15f, 0.78f, 0.85f, 0.78f)))
        t["十"] = listOf(listOf(line(0.15f, 0.42f, 0.85f, 0.42f), line(0.5f, 0.12f, 0.5f, 0.9f)))
        t["人"] = listOf(listOf(line(0.5f, 0.15f, 0.22f, 0.82f), line(0.38f, 0.48f, 0.85f, 0.85f)))
        t["八"] = listOf(listOf(line(0.45f, 0.15f, 0.15f, 0.82f), line(0.55f, 0.15f, 0.85f, 0.82f)))
        t["七"] = listOf(listOf(line(0.1f, 0.35f, 0.9f, 0.3f), line(0.62f, 0.3f, 0.7f, 0.85f, 10)))
        t["九"] = listOf(listOf(line(0.62f, 0.15f, 0.25f, 0.68f), line(0.18f, 0.4f, 0.82f, 0.45f, 8) + line(0.82f, 0.45f, 0.68f, 0.85f, 8)))
        t["口"] = listOf(listOf(
            line(0.28f, 0.22f, 0.72f, 0.22f, 8) + line(0.72f, 0.22f, 0.72f, 0.78f, 8) +
                line(0.72f, 0.78f, 0.28f, 0.78f, 8) + line(0.28f, 0.78f, 0.28f, 0.22f, 8)))
        return t
    }
}
