package com.glasskey.ime.hwr

import android.content.Context
import android.graphics.PointF
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.glasskey.ime.ui.Theme

/**
 * 手写面板（§5.5 / v1.5.3 重构）：
 *  - 画布高度固定为键盘区域高度（不再依赖 weight 撑满/塌陷，修复"占全屏"）；
 *  - 抬笔识别后**不再自动上屏/退出**：候选进候选栏（首选高亮待确认），
 *    点候选或按"空格"上屏首选，可连续书写（修复"画两下就退出"）；
 *  - 识别全程 try 保护，异常不再带崩输入法进程。
 */
class HandwritingPanel(context: Context) : LinearLayout(context) {

    var onCommitText: ((String) -> Unit)? = null       // 候选/首选上屏
    var onCandidates: ((List<String>) -> Unit)? = null // 识别结果送候选栏融合
    var onBackspace: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var engine: HwrEngine = BuiltinHwrEngine()
    private val canvas: HandwritingView = HandwritingView(context)

    /** v1.4：手写模型接入点（服务层注入 HwrEngineFactory 产物；未注入用内置回退） */
    fun setEngine(e: HwrEngine) {
        // v1.6.0：替换前释放旧引擎（ONNX native session / 线程池，防内存泄漏）
        if (engine !== e) engine.close()
        engine = if (e.ready) e else BuiltinHwrEngine()
        status.text = "手写模式 · ${engine.displayName}"
    }

    private val status = TextView(context).apply {
        textSize = 12f
        alpha = 0.75f
        setPadding((d * 12).toInt(), (d * 6).toInt(), (d * 12).toInt(), 0)
    }

    /** 当前识别首选（空格键上屏用） */
    private var topCandidate: String? = null

    init {
        orientation = VERTICAL
        // v1.5.3：画布高度固定 ≈ 键盘区域（随设置里的键盘高度缩放），不再占全屏
        val scale = try {
            com.glasskey.app.GkPrefs.keyHeightScale(context) / 100f
        } catch (_: Throwable) { 1f }
        val canvasH = (d * 210f * scale.coerceIn(0.8f, 1.3f)).toInt()
        canvas.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, canvasH)
        canvas.onStrokeFinished = { strokes -> recognize(strokes) }
        canvas.onSwipeDown = { onBack?.invoke() }

        val ops = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        fun btn(label: String, weight: Float, block: () -> Unit) =
            Button(context).apply {
                text = label
                textSize = 14f
                setOnClickListener { block() }
                ops.addView(this, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, weight))
            }
        btn("清空", 1f) { canvas.clear(); topCandidate = null; onCandidates?.invoke(emptyList()) }
        btn("退格", 1f) { onBackspace?.invoke() }
        btn("上屏首选", 1f) { commitTop() }
        btn("返回", 1f) { onBack?.invoke() }

        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(canvas)
        addView(ops, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.text = "手写模式 · ${engine.displayName}"
    }

    fun setTheme(theme: Theme) {
        canvas.theme = theme
    }

    private fun commitTop() {
        val w = topCandidate ?: return
        onCommitText?.invoke(w)
        canvas.clear()
        topCandidate = null
        onCandidates?.invoke(emptyList())
    }

    /** 服务侧候选栏点选回调：上屏指定候选并复位画布 */
    fun commitCandidate(word: String) {
        onCommitText?.invoke(word)
        canvas.clear()
        topCandidate = null
        onCandidates?.invoke(emptyList())
    }

    private fun recognize(strokes: List<List<PointF>>) {
        val cands = try {
            engine.recognize(strokes)
        } catch (t: Throwable) {
            android.util.Log.w("GkHwr", "recognize failed", t)
            emptyList()
        }
        if (cands.isEmpty()) {
            status.text = "未识别，可继续书写或点「清空」重写"
            return
        }
        // v1.5.3：候选待确认（不自动上屏、不清画布）——连续书写不退出；
        // 首选进候选栏首位，点选或「上屏首选」确认
        topCandidate = cands[0]
        onCandidates?.invoke(cands)
        status.text = "识别：${cands.take(3).joinToString(" / ")}（点候选上屏，可继续写）"
    }
}
