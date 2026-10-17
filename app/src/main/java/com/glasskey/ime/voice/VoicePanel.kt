package com.glasskey.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.glasskey.app.MainActivity
import com.glasskey.ime.ui.Theme

/**
 * 语音面板（§5.6）：
 * 默认"点击开始 / 点击结束"模式；能量 VAD 静音 800ms 自动停；
 * 流式中间结果灰显预览，停止定稿；录音权限运行时申请（经主 App，IME 无 Activity）。
 * v1.2：模型随 APK assets 内置（约 26MB），首次进入面板后台预热识别器，
 * 录音即点即用（sherpa-onnx 流式 Zipformer 中文 INT8，完全离线）。
 */
class VoicePanel(context: Context) : LinearLayout(context) {

    var onCommitText: ((String) -> Unit)? = null
    var onBack: (() -> Unit)? = null

    /** v1.4 语音后处理（服务层注入词库）：ASR 文本 → 用户词库校准 → 标点恢复 → 上屏 */
    var postProcessor: VoicePostProcessor? = null

    /** v1.4 声音校平（服务层可经 GkPrefs 开关；默认开） */
    private val leveler = AudioLeveler()

    private val d = resources.displayMetrics.density
    private val statusView = TextView(context)
    private val previewView = TextView(context)
    private val levelView = View(context)
    private val actionBtn = Button(context)

    private var collector: AudioCollector? = null
    private var asr: AsrEngine? = null
    private var recording = false

    /** 长按空格语音通道（v1.3）：流式 partial 外部回调（服务侧 HUD 显示） */
    private var quickPartialSink: ((String) -> Unit)? = null
    private var lastPartialRescoreMs = 0L
    private var partialRescoreCache: String? = null

    // v1.5.3 录音先行：识别器延迟就绪期间的音频缓存与后台预热线程
    private val pendingAudio = ArrayList<ShortArray>()
    private var pendingLen = 0
    @Volatile private var liveEngine: AsrEngine? = null
    private var warmThread: Thread? = null

    /** v1.5.4：静音自动停开关（服务层按用户设置注入；默认 false=持续识别） */
    var voiceSilenceAutoStop: Boolean = false

    /** 预热锁与状态：面板创建即后台加载模型，首次点击无需等待 */
    private val warmLock = Any()
    private var warmed = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding((d * 16).toInt(), (d * 10).toInt(), (d * 16).toInt(), (d * 10).toInt())

        statusView.textSize = 14f
        statusView.gravity = Gravity.CENTER
        previewView.textSize = 16f
        previewView.gravity = Gravity.CENTER
        previewView.alpha = 0.65f
        previewView.setPadding(0, (d * 10).toInt(), 0, (d * 10).toInt())
        levelView.layoutParams = LayoutParams((d * 120).toInt(), (d * 6).toInt())
        styleLevel(0f)

        actionBtn.text = "开始录音"
        actionBtn.setOnClickListener { toggle() }

        val ops = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun btn(label: String, weight: Float, block: () -> Unit) =
            Button(context).apply {
                text = label; textSize = 13f
                setOnClickListener { block() }
                ops.addView(this, LayoutParams(0, LayoutParams.WRAP_CONTENT, weight))
            }
        btn("定稿上屏", 1f) { finalize() }
        btn("返回键盘", 1f) { stopAll(); onBack?.invoke() }

        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(levelView)
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(actionBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(ops, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refreshGate()
        warmUp()
    }

    /** 后台预热识别器：模型加载 1-2s，避免首次录音点击卡 UI */
    private fun warmUp() {
        Thread {
            ensureAsr()
            post {
                if (!recording && warmed &&
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    statusView.text = "点击开始 · 持续识别，点停止上屏"
                }
            }
        }.start()
    }

    /** 获取（或等待预热中的）识别器；模型缺失返回 null */
    private fun ensureAsr(): AsrEngine? {
        synchronized(warmLock) {
            asr?.let { return it }
            // v1.5.5：服务启动时的预热缓存优先（冷启动近乎 0 等待）
            AsrCache.take()?.let {
                asr = it
                warmed = it !is PlaceholderAsrEngine
                return it
            }
            if (!VoiceModels.asrReady(context)) return null
            // v1.6.3：实测模型加载前后内存差（写开发者日志，回答"语音模型占多少"）
            val pssBefore = com.glasskey.ime.dev.GkMemMonitor.pss(context)
            val engine = AsrEngineFactory.create(context)
            val pssAfter = com.glasskey.ime.dev.GkMemMonitor.pss(context)
            com.glasskey.ime.dev.GkLogger.log("voice",
                "asr loaded pss ${pssBefore}→${pssAfter}MB (model≈${pssAfter - pssBefore}MB)")
            asr = engine
            warmed = engine !is PlaceholderAsrEngine
            return engine
        }
    }

    fun setTheme(theme: Theme) {
        setBackgroundColor(theme.panelBg)
        statusView.setTextColor(theme.candidateText)
        previewView.setTextColor(theme.candidateHint)
    }

    /** 权限/模型门态刷新（v1.3 修复：原 private 且仅 init 调用一次，
     *  授权返回后按钮永远停在"去授权"死循环；现公开，switchPanel(VOICE) 时重刷） */
    fun refreshGate() {
        val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val modelReady = VoiceModels.asrReady(context)
        when {
            !hasPermission -> {
                statusView.text = "需要麦克风权限（仅本地处理，不上传）"
                actionBtn.text = "去授权"
                actionBtn.setOnClickListener {
                    // IME 无 Activity，经主 App 申请（§5.6 权限拒绝 → 图文引导）
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(MainActivity.EXTRA_REQUEST_MIC, true))
                    Toast.makeText(context, "在打开的页面中允许麦克风", Toast.LENGTH_LONG).show()
                }
                levelView.visibility = GONE
            }
            !modelReady -> {
                statusView.text = "语音识别模型未安装（完全离线推理）"
                previewView.text = VoiceModels.describePackage()
                actionBtn.text = "开始录音（试音）"
                actionBtn.setOnClickListener { toggle() }
                levelView.visibility = VISIBLE
            }
            else -> {
                statusView.text = "点击开始 · 持续识别，点停止上屏"
                actionBtn.text = "开始录音"
                actionBtn.setOnClickListener { toggle() }
                levelView.visibility = VISIBLE
            }
        }
    }

    private fun toggle() {
        if (recording) {
            finalize()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            refreshGate()
            return
        }
        // 通话/媒体音量路由校准（避免"录音了但听不见提示"的 ROM 怪癖）
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .isMicrophoneMute = false
        leveler.reset()  // v1.4：每次会话重置校平状态（AGC/滤波器）
        // v1.5.3 录音先行：识别器未就绪时**立即开始录音**，音频先入缓存，
        // 模型后台预热完成后异步回放缓存再接续实时推理 —— 用户零感知延迟
        val ready = synchronized(warmLock) { if (warmed) asr else null }
        pendingAudio.clear(); pendingLen = 0
        if (ready != null) {
            ready.startSession()
            liveEngine = ready
        } else {
            liveEngine = null
            statusView.text = "正在准备模型…（已开始录音，无需等待）"
            warmThread = Thread {
                val eng = try { ensureAsr() } catch (_: Throwable) { null }
                if (eng != null) {
                    eng.startSession()
                    synchronized(pendingAudio) {
                        for (buf in pendingAudio) eng.feedAudio(buf, buf.size)
                        pendingAudio.clear(); pendingLen = 0
                    }
                    liveEngine = eng
                    post { if (recording) statusView.text = "录音中…" }
                }
            }.also { it.start() }
        }
        collector = AudioCollector(object : AudioCollector.Listener {
            override fun onLevel(rms: Float) { post { styleLevel(rms) } }
            override fun onPartialText(text: String) {
                post { previewView.text = text }
                post { quickPartialSink?.invoke(text) }
            }
            override fun onSilenceTimeout() { post { finalize(auto = true) } }
            override fun onError(message: String) { post {
                statusView.text = message
                stopAll()
            } }
            // v1.2：20ms 帧送 sherpa-onnx 流式解码；v1.4：先经声音校平（高通→AGC→限幅）
            // v1.5.3：识别器未就绪时音频入缓存（上限 90s），warm 后自动回放
            override fun onAudioFrame(pcm: ShortArray, len: Int): String {
                leveler.process(pcm, len)
                val eng = liveEngine
                if (eng == null) {
                    synchronized(pendingAudio) {
                        if (pendingLen < AudioCollector.SAMPLE_RATE * 90) {
                            pendingAudio.add(pcm.copyOf(len)); pendingLen += len
                        }
                    }
                    return ""
                }
                val t = eng.feedAudio(pcm, len)
                // v1.5.2：流式 partial 同步词库组句（节流 300ms），预览即所见
                val now = System.currentTimeMillis()
                if (t.isNotEmpty() && now - lastPartialRescoreMs > 300) {
                    lastPartialRescoreMs = now
                    val pp = postProcessor
                    if (pp?.rescorer?.ready == true) {
                        partialRescoreCache = try {
                            pp.rescorer!!.rescore(t).text
                        } catch (_: Throwable) { t }
                    }
                }
                return if (t.isEmpty()) "" else (partialRescoreCache ?: t)
            }
        })
        // v1.5.4：静音不再自动停（持续识别直到手动停）；设置页可恢复自动停
        collector!!.silenceAutoStop = voiceSilenceAutoStop
        if (!collector!!.start()) {
            liveEngine?.release()
            stopAll()
            return
        }
        recording = true
        actionBtn.text = "停止"
        lastPartialRescoreMs = 0L
        partialRescoreCache = null
        statusView.text = "录音中…"
        previewView.text = ""
    }

    private fun finalize(auto: Boolean = false) {
        if (!recording) return
        collector?.stop()
        // v1.5.3：识别器就绪即定稿；未就绪等 warm（≤8s），回放缓存后再定稿（不丢内容）
        val eng = liveEngine
        val finalText: String = if (eng != null) {
            eng.stopSession()
        } else {
            try { warmThread?.join(8000) } catch (_: InterruptedException) {}
            val e2 = liveEngine
            if (e2 != null) {
                synchronized(pendingAudio) {
                    for (buf in pendingAudio) e2.feedAudio(buf, buf.size)
                    pendingAudio.clear(); pendingLen = 0
                }
                e2.stopSession()
            } else ""
        }
        liveEngine = null
        recording = false
        actionBtn.text = "开始录音"
        styleLevel(0f)
        statusView.text = if (auto) "已自动停止（静音）" else "已停止"
        if (finalText.isNotEmpty()) {
            // v1.4 后处理链：ASR 原始文本 → 用户词库校准/纠错 → 标点恢复 → 上屏
            // 静音自动停（auto=true）视为高置信句界；手动停止按长停顿处理
            val (processed, log) = try {
                postProcessor?.process(
                    VoicePostProcessor.Segment(
                        text = finalText,
                        trailingSilenceMs = if (auto) 800L else 450L,
                    )
                ) ?: (finalText to emptyList())
            } catch (t: Throwable) {
                finalText to listOf("post-process error: ${t.message}")  // 可回退：后处理异常原样上屏
            }
            log.forEach { android.util.Log.d("GkVoice", it) }
            // v1.5.2 置信度上屏决策：≥CONF_AUTO 直接上屏；CONF_AUTO~CONF_LOW
            // 上屏并在预览警示；< CONF_LOW 不上屏，显示在预览区点击确认
            val conf = postProcessor?.lastRescore?.confidence ?: 1.0
            if (conf >= CONF_LOW) {
                onCommitText?.invoke(processed)
                previewView.text = "已上屏：$processed" +
                    if (conf < CONF_AUTO) "（置信度偏低 ${"%.0f".format(conf * 100)}%，请核对）" else ""
            } else {
                previewView.text = "置信度低（${"%.0f".format(conf * 100)}%），点击此处上屏：$processed"
                previewView.isClickable = true
                previewView.setOnClickListener {
                    onCommitText?.invoke(processed)
                    previewView.text = "已上屏：$processed"
                    previewView.isClickable = false
                    previewView.setOnClickListener(null)
                }
            }
        } else {
            previewView.text = if (warmed) "（未识别到语音）"
            else "模型未安装：识别结果为空 · ${VoiceModels.describePackage()}"
        }
    }

    private fun stopAll() {
        if (recording) {
            collector?.stop()
            liveEngine?.stopSession()
            liveEngine = null
            recording = false
        }
        styleLevel(0f)
    }

    /** 面板隐藏时停止录音；识别器归还 AsrCache 常驻（v1.6.3：
     *  卸载时机由「语音引擎常驻时长」设置统一管理，不再每次销毁——
     *  避免每次语音输入都等模型重新加载） */
    fun onPanelHidden() {
        stopAll()
        synchronized(warmLock) {
            AsrCache.hold(asr, context)
            asr = null
            warmed = false
        }
    }

    // —— 长按空格语音快速通道（v1.3，§5.6 增：按住说话、松开上屏）——

    /** 快速开始：权限满足则直接开录（复用面板录音链路），partial 经 [onPartial]
     *  外抛给服务侧 HUD；权限不满足返回 false（服务侧提示，不打断跳授权） */
    fun quickStart(onPartial: (String) -> Unit): Boolean {
        val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false
        if (recording) return true
        quickPartialSink = onPartial
        startRecording()
        return true
    }

    /** 快速停止（空格松开）：停录并定稿上屏（finalize 内部走 onCommitText） */
    fun quickStop() {
        quickPartialSink = null
        if (!recording) return
        finalize()
    }

    /** 快速通道是否正在录音（服务侧 HUD 状态判断） */
    fun isQuickRecording(): Boolean = recording

    private fun styleLevel(level: Float) {
        levelView.background = GradientDrawable().apply {
            cornerRadius = d * 3f
            setColor(android.graphics.Color.argb(
                (80 + 175 * level).toInt(), 111, 168, 255))
        }
        val lp = levelView.layoutParams as LayoutParams
        lp.width = (d * 40 + d * 160 * level).toInt()
        levelView.layoutParams = lp
    }

    private fun post(block: () -> Unit) = postDelayed(block, 0)

    companion object {
        /** 置信度阈值：≥ 自动上屏（无警示）；≥ 低置信上屏（警示）；< 待确认 */
        const val CONF_AUTO = 0.85
        const val CONF_LOW = 0.45
    }
}
