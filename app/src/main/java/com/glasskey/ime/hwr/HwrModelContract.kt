package com.glasskey.ime.hwr

import java.io.File

/**
 * 手写模型（资源包 ONNX，v1.4）接入契约与候选上限。
 *
 * 接入点（§5.5 已有 IHwrEngine 可插拔接口，本版本接入真实模型后端）：
 *   HandwritingPanel.engine ← HwrEngineFactory.create()
 *     ├─ OnnxHwrEngine   ：models/hwr/hwr_model.int8.onnx + hwr_labels.txt 就绪时
 *     └─ BuiltinHwrEngine：模型缺失/加载失败/超时 → 内置 $P 点云识别（回退）
 *
 * 模型文件（资源包，不进 APK）：
 *   filesDir/models/hwr/hwr_model.int8.onnx   —— INT8 ONNX 手写汉字模型（GB2312 一二级）
 *   filesDir/models/hwr/hwr_labels.txt        —— 输出类别表（每行一字，行号=输出索引）
 *
 * 接口：与内置实现共用 HwrEngine（strokes → top-k 候选字）；
 * 输出上限统一 [HwrLimits.CANDIDATE_LIMIT]=30，由本类与面板共同保证。
 */
object HwrLimits {
    /** 手写候选上限（= 普通候选上限，统一配置） */
    const val CANDIDATE_LIMIT = 30

    /** 单次识别超时预算（ms），超时走回退并记日志 */
    const val RECOGNIZE_TIMEOUT_MS = 1500L
}

/** 模型契约（纯 JVM 可测）：文件就绪判定 + 标签表解析 + 输出截断 */
object HwrModelContract {

    const val MODEL_DIR = "models/hwr"
    const val MODEL_FILE = "hwr_model.int8.onnx"
    const val LABELS_FILE = "hwr_labels.txt"

    fun modelDir(root: File): File = File(root, MODEL_DIR)
    fun modelFile(root: File): File = File(modelDir(root), MODEL_FILE)
    fun labelsFile(root: File): File = File(modelDir(root), LABELS_FILE)

    /**
     * 模型是否就绪：onnx（>1MB，拒半截/损坏文件，真模型 8.3MB）+ labels 齐全非空。
     * v1.5.7：原实现只查非空 → 并发部署损坏的半截文件也会被当"就绪"。
     */
    fun ready(root: File): Boolean =
        modelFile(root).isFile && modelFile(root).length() > 1_000_000L &&
            labelsFile(root).isFile && labelsFile(root).length() > 0

    /**
     * 解析标签表：每行一个字符，允许 "索引 字" / "字" 两种格式；
     * 空行/重复跳过；异常字符（空白/控制符）跳过。
     */
    fun parseLabels(text: String): List<String> =
        text.lineByLine().filter { it.isNotEmpty() && it.length <= 4 && !it.first().isWhitespace() }

    private fun String.lineByLine(): List<String> =
        this.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
            .map { line ->
                // "12 中" → "中"；"中" → "中"
                val sp = line.split(Regex("\\s+"))
                if (sp.size >= 2 && sp[0].toIntOrNull() != null) sp[1] else sp[0]
            }

    /** 输出截断：labels 越界的 logits 丢弃，返回 ≤ limit 的候选 */
    fun topCandidates(logits: FloatArray, labels: List<String>, limit: Int): List<String> {
        if (labels.isEmpty() || logits.isEmpty()) return emptyList()
        val n = minOf(logits.size, labels.size)
        return logits.indices.take(n)
            .sortedByDescending { logits[it] }
            .take(limit)
            .map { labels[it] }
    }
}
