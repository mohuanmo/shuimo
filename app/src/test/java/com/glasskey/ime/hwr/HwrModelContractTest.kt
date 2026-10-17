package com.glasskey.ime.hwr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 手写模型契约：模型就绪判定 / 标签表解析 / 候选上限 30 / 截断 */
class HwrModelContractTest {

    @Test
    fun ready_requiresBothFiles() {
        val root = File.createTempFile("hwr", "root").let { it.delete(); it.mkdirs(); it }
        assertFalse(HwrModelContract.ready(root))
        HwrModelContract.modelDir(root).mkdirs()
        HwrModelContract.modelFile(root).writeText("fake")
        assertFalse("仅 onnx 不够", HwrModelContract.ready(root))
        HwrModelContract.labelsFile(root).writeText("一\n二\n")
        assertTrue(HwrModelContract.ready(root))
        root.deleteRecursively()
    }

    @Test
    fun parseLabels_indexedAndPlain() {
        val labels = HwrModelContract.parseLabels("0 一\n1 二\n\n2 三\n中\n \n4 x")
        assertEquals(listOf("一", "二", "三", "中", "x"), labels)
    }

    @Test
    fun topCandidates_limit30_andSorted() {
        val labels = (0 until 40).map { it.toString() }
        val logits = FloatArray(40) { it.toFloat() }
        val top = HwrModelContract.topCandidates(logits, labels, HwrLimits.CANDIDATE_LIMIT)
        assertEquals(30, top.size)
        assertEquals("39", top.first())
        assertEquals(30, HwrLimits.CANDIDATE_LIMIT)  // 统一候选上限 30
    }

    @Test
    fun topCandidates_outOfRangeLogitsDropped() {
        val labels = listOf("一", "二")
        val logits = FloatArray(10) { it.toFloat() }  // 模型输出 10，标签仅 2
        val top = HwrModelContract.topCandidates(logits, labels, 30)
        assertEquals(2, top.size)
        assertEquals("二", top.first())
    }

    @Test
    fun timeoutBudget_reasonable() {
        assertTrue("识别超时预算应 ≤300ms（实时性）", HwrLimits.RECOGNIZE_TIMEOUT_MS <= 300)
        assertFalse(HwrLimits.RECOGNIZE_TIMEOUT_MS < 50)
    }
}
