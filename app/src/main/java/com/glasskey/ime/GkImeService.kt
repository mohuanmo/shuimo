package com.glasskey.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.glasskey.app.GkPrefs
import com.glasskey.ime.clipboard.ClipDb
import com.glasskey.ime.clipboard.ClipPanel
import com.glasskey.ime.clipboard.ClipboardModule
import com.glasskey.ime.data.UserDictDb
import com.glasskey.ime.engine.GkEngine
import com.glasskey.ime.engine.GkFuzzy
import com.glasskey.ime.engine.GkLayout
import com.glasskey.ime.floating.FloatingOverlayController
import com.glasskey.ime.hwr.HandwritingPanel
import com.glasskey.ime.hwr.HwrEngineFactory
import com.glasskey.ime.input.EditorMapper
import com.glasskey.ime.input.LatinCapHelper
import com.glasskey.ime.pinyin.ContinuationResolver
import com.glasskey.ime.ui.CandidateBar
import com.glasskey.ime.ui.FloatingContainer
import com.glasskey.ime.ui.KeyboardDefs
import com.glasskey.ime.ui.KeyboardView
import com.glasskey.ime.ui.SymbolPanel
import com.glasskey.ime.ui.Theme
import com.glasskey.ime.ui.ToolbarView
import com.glasskey.ime.ui.withKeyLabel
import com.glasskey.ime.ui.numpad
import com.glasskey.ime.voice.VoicePanel
import com.glasskey.ime.voice.VoicePostProcessor
import com.glasskey.ime.voice.VoiceRescorer
import java.io.File

/**
 * GlassKey 输入法服务（§5.1）：
 * 击键 → C++ 引擎合成 → 候选条展示 → 提交上屏 + 用户词回写。
 *
 * v1.1（M2 第二部分，§5.5-5.8）：
 * - 候选栏三段式：合成区显示用户所打拼音/英文原文（§4.2.1）+ 横滑候选；
 * - 工具栏统一入口：剪贴板 / 手写 / 语音 / 悬浮 / 符号 / 收起；
 * - 剪贴板：焦点捕获、粘贴气泡（10s）、验证码/链接/电话一键上屏、历史面板（§5.8）；
 * - 手写：画布抬笔即识别，候选与拼音同栏融合（§5.5）；
 * - 语音：本地 VAD 录音面板，模型资源包引导（§5.6）；
 * - 悬浮键盘：窗口内浮动容器（拖动/缩放/透明度/磁吸，§5.7 路线 A）；
 * - INTERNET 权限仅用于用户主动添加的词库订阅下载；持久化单向（SQLite → 引擎装载；提交 → SQLite）。
 */
class GkImeService : InputMethodService() {

    /** 功能面板模式 */
    private enum class Panel { KEYBOARD, CLIP, HWR, VOICE, SYMBOL }

    @Volatile
    private var engine: GkEngine? = null
    private var db: UserDictDb? = null
    private var clipDb: ClipDb? = null

    /** 上次已捕获入库的剪贴板文本（去重，§5.8） */
    @Volatile
    private var lastClipText: String? = null

    // ── 视图（onCreateInputView 惰性创建，访问前一律 isInitialized 防护）──
    private lateinit var inputRoot: FrameLayout
    /** v1.6.0：悬浮期间暂存 IME 主窗口原背景（退出悬浮还原，修白条） */
    private var imeWindowBg: android.graphics.drawable.Drawable? = null
    private lateinit var mainColumn: LinearLayout
    private lateinit var pasteBubble: TextView
    private lateinit var candidateBar: CandidateBar
    private lateinit var toolbar: ToolbarView
    private lateinit var panelHost: FrameLayout
    private lateinit var keyboardView: KeyboardView
    private lateinit var floatingSpacer: View
    private lateinit var floatingLayer: FloatingContainer
    // v1.6.5：功能面板惰性创建——onCreateInputView 主线程只建键盘主视图；
    // 剪贴板/手写/语音/符号面板首次切到时才构造（此前四个面板视图 + 语音
    // 重打分器注入全部在弹出路径上同步完成，是"点输入框 → 键盘弹出"偏慢的
    // 残留主因）。访问一律走 ensureXxxPanel()，可空字段仅判断"是否已创建"。
    private var clipPanel: ClipPanel? = null
    private var hwrPanel: HandwritingPanel? = null
    private var voicePanel: VoicePanel? = null
    private var symbolPanel: SymbolPanel? = null

    private fun ensureClipPanel(): ClipPanel =
        clipPanel ?: ClipPanel(this).apply {
            clipPanel = this
            onCommit = { text -> commitText(text); switchPanel(Panel.KEYBOARD) }
            onBack = { switchPanel(Panel.KEYBOARD) }
            panelHost.addView(this, hostParams())
            visibility = View.GONE
        }

    private fun ensureHwrPanel(): HandwritingPanel =
        hwrPanel ?: HandwritingPanel(this).apply {
            hwrPanel = this
            onCommitText = { text ->
                commitText(text)
                hwrCandidates = emptyList()
                refreshCandidates()
            }
            onCandidates = { cands ->
                hwrCandidates = cands
                refreshCandidates()
            }
            onBackspace = {
                val e = engine
                if (e != null && e.backspace() == 0) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    // v1.5.3 学习记忆：语音窗口内退格删除已上屏文本 → 记删字数
                    noteVoiceWindowBackspace()
                }
                refreshCandidates()
            }
            onBack = { switchPanel(Panel.KEYBOARD) }
            panelHost.addView(this, hostParams())
            visibility = View.GONE
            // v1.6.2：UI 先行——主线程只挂零成本内置 $P 识别器，
            // ONNX 模型（session 创建 + 预热推理）全部移到后台线程。
            setEngine(HwrEngineFactory.builtin())
            // v1.5.7：模型部署 + 存量损坏自愈 + 就绪切换（部署唯一入口，见 createEngine 注释）
            Thread {
                try {
                    val root = File(filesDir, "models")
                    fun deployHwr(): Boolean {
                        val m = deployAsset("models/hwr/hwr_model.int8.onnx")
                        val l = deployAsset("models/hwr/hwr_labels.txt")
                        return m != null && l != null && m.length() > 0 && l.length() > 0
                    }
                    deployHwr()
                    var eng = HwrEngineFactory.create(root)
                    if (!eng.ready) {
                        // 存量自愈：旧版本并发部署可能留下损坏/半截模型文件，
                        // ready() 只查非空无法识别 → 删除后重新部署一次再试
                        android.util.Log.w("GkHwr", "hwr engine not ready after deploy, wiping and redeploying")
                        com.glasskey.ime.hwr.HwrModelContract.modelFile(root).delete()
                        com.glasskey.ime.hwr.HwrModelContract.labelsFile(root).delete()
                        deployHwr()
                        eng = HwrEngineFactory.create(root)
                    }
                    if (eng.ready) post { setEngine(eng) }
                    android.util.Log.i("GkHwr",
                        "hwr engine ready=${eng.ready} impl=${eng.javaClass.simpleName}")
                    com.glasskey.ime.dev.GkLogger.log("hwr",
                        "deploy done ready=${eng.ready} impl=${eng.javaClass.simpleName} " +
                            "model=${com.glasskey.ime.hwr.HwrModelContract.modelFile(root).length()}B")
                } catch (t: Throwable) {
                    android.util.Log.w("GkHwr", "hwr deploy thread failed", t)
                }
            }.apply { name = "gk-hwr-load"; isDaemon = true }.start()
        }

    private fun ensureVoicePanel(): VoicePanel =
        voicePanel ?: VoicePanel(this).apply {
            voicePanel = this
            // v1.4：语音后处理 —— 用户词库校准/纠错 + 标点恢复（链路：ASR→组句→校准→标点→上屏）
            // v1.5.2：注入词库组句重打分器（后台懒加载 assets 语音词表）
            VoiceRescorer(assets).also {
                voiceRescorer = it
                it.warmUp()
            }.let { postProcessor = VoicePostProcessor(voiceLexicon()).apply { rescorer = it } }
            // v1.5.4：静音自动停由用户设置控制（默认持续识别直到手动停）
            voiceSilenceAutoStop = com.glasskey.app.GkPrefs.voiceAutoStop(this@GkImeService)
            onCommitText = { text -> commitText(text, fromVoice = true) }
            onBack = { switchPanel(Panel.KEYBOARD) }
            panelHost.addView(this, hostParams())
            visibility = View.GONE
        }

    private fun ensureSymbolPanel(): SymbolPanel =
        symbolPanel ?: SymbolPanel(this).apply {
            symbolPanel = this
            onCommit = { text -> commitText(text) }
            onBack = { switchPanel(Panel.KEYBOARD) }
            panelHost.addView(this, hostParams())
            visibility = View.GONE
        }

    // 当前键盘形态 / 面板
    private var layoutMode: Int = GkLayout.PINYIN_26
    /** v1.7.1：数字小键盘模式（键盘「123」键切换，NumPad 独立布局） */
    private var numpadMode = false
    private var capMode: LatinCapHelper.Mode = LatinCapHelper.Mode.OFF
    private var currentEditorLayout: Int = EditorMapper.LAYOUT_PINYIN
    private var panelMode: Panel = Panel.KEYBOARD
    private var floating = false

    // 手写识别候选（与引擎候选分通道，§5.5 同栏融合）
    private var hwrCandidates: List<String> = emptyList()

    // v1.5.8 下文预测：上一个上屏 token + 当前预测候选（合成区为空时显示）
    private var lastCommitted = ""
    private var predictCands: List<String> = emptyList()

    // v1.7.2：实际展示的候选列表与来源标记（点击提交通道一致性）
    private var shownCands: List<String> = emptyList()
    private var candsFromEngine = true

    // ── v1.4 连续拼音解析（引擎无候选时的第二层兜底/增强）──
    private var contResolver: ContinuationResolver? = null
    private val contCache = object : LinkedHashMap<String, ContinuationResolver.Result>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContinuationResolver.Result>) =
            size > 128
    }
    private var contPendingCommit: Runnable? = null
    private val contUiHandler = Handler(Looper.getMainLooper())
    private var contLexiconSnapshot: List<Triple<String, String, Int>> = emptyList()
    private var contLexiconLoaded = false

    // 剪贴板气泡
    private val uiHandler = Handler(Looper.getMainLooper())
    private var bubbleDismiss: Runnable? = null

    // 路线 B 真悬浮窗控制器（v1.3，onCreateInputView 重建时重置）
    private var overlayCtl: FloatingOverlayController? = null
    private var voiceRescorer: VoiceRescorer? = null

    // 长按空格语音流式文本（v1.6.5）：见 showVoiceHud 注释，写候选栏合成层

    // ── 生命周期 ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        com.glasskey.app.CrashGuard.install(this)
        // v1.5.8：开发者日志（设置里开启后记录运行事件）
        com.glasskey.ime.dev.GkLogger.enabled = com.glasskey.app.GkPrefs.devLogEnabled(this)
        com.glasskey.ime.dev.GkLogger.log("ime", "service onCreate")
        // v1.5.5：启动即后台预热语音识别器（首次语音输入近零等待）
        com.glasskey.ime.voice.AsrCache.prewarm(this)
        // v1.5.7：九键简拼（首字母）索引后台构建（6 万高频词 → T9 数字串）
        com.glasskey.ime.t9.T9InitialIndex.buildAsync(this)
        // v1.6.8：订阅定时自动更新（内部有间隔判定，未到期零成本返回；
        // 键盘每天至少弹一次，等效每日轮询，无需常驻后台）
        com.glasskey.app.LexiconStore.deployBuiltins(this)
        com.glasskey.app.LexiconStore.maybeAutoUpdate(this)
        db = UserDictDb(this)
        clipDb = ClipDb(this)
        // v1.6.2：引擎改为后台加载（此前 onCreate 主线程同步 JNI open 词典
        // + 装载用户词，直接拖慢"点输入框 → 键盘弹出"；UI 侧 refreshCandidates
        // 对 engine=null 已有安全分支，加载完成前按键只显示空候选）
        Thread {
            val t0 = System.currentTimeMillis()
            val e = try { createEngine() } catch (t: Throwable) {
                com.glasskey.ime.dev.GkLogger.logThrowable("ime", "createEngine failed", t)
                null
            }
            engine = e
            com.glasskey.ime.dev.GkLogger.log("ime",
                "engine ready in ${System.currentTimeMillis() - t0}ms ok=${e != null}")
            // 加载完成时若输入视图已显示（用户正在等候选），立即补一次刷新
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (::candidateBar.isInitialized) refreshCandidates()
            }
        }.apply { name = "gk-engine-load"; isDaemon = true }.start()
    }

    override fun onDestroy() {
        // 服务销毁：收回路线 B 系统悬浮窗，避免窗口泄漏
        if (overlayCtl?.isShowing == true) overlayCtl?.hide()
        overlayCtl = null
        engine?.sessionEnd()
        engine?.close()
        engine = null
        db?.close()
        db = null
        clipDb?.close()
        clipDb = null
        super.onDestroy()
    }

    /** 词典部署（assets → filesDir，仅首次）+ 引擎创建 + 用户词装载 */
    private fun createEngine(): GkEngine? {
        val zh = deployAsset("dict/gk_zh.gkdt") ?: return null
        val en = deployAsset("dict/gk_en.gkdt")
        // v1.5.7：手写模型部署统一收口到 onCreateInputView 的后台线程
        //（v1.5.4-1.5.6 此处与 onCreateInputView 各起一线程并发写同一文件，
        //  非原子拷贝互相踩踏 → 模型损坏且损坏文件永久跳过重拷 → ORT init 永久失败）
        val e = GkEngine(
            dictPath = zh.absolutePath,
            latinPath = en?.absolutePath,
            fuzzyMask = GkPrefs.fuzzyMask(this),
            beamWidth = 10,
            // v1.4：候选上限统一配置（默认 30），普通词/单字/手写共用
            maxCandidates = GkPrefs.candidateLimit(this),
            learningEnabled = GkPrefs.learning(this),
        )
        if (!e.open()) {
            com.glasskey.ime.dev.GkLogger.log("ime", "engine open FAILED dict=${zh.absolutePath}")
            return null
        }
        com.glasskey.ime.dev.GkLogger.log("ime", "engine opened OK")
        e.setFuzzy(GkPrefs.fuzzyMask(this))
        e.setLearning(GkPrefs.learning(this))
        // 用户词装载（§4.5 blob）
        val blob = UserDictDb.toBlob(db!!.all())
        if (blob.isNotEmpty()) e.loadUserWords(blob)
        return e
    }

    private fun deployAsset(assetPath: String): File? {
        return try {
            val out = File(filesDir, assetPath)
            if (!out.isFile || out.length() == 0L) {
                out.parentFile?.mkdirs()
                // v1.5.7：先写临时文件再原子 rename —— 旧实现直接写目标文件，
                // 拷贝中途被并发线程/进程杀死会留下半截文件且下次跳过重拷
                val tmp = File(out.parentFile, out.name + ".tmp")
                assets.open(assetPath).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.delete()
                    return if (out.isFile && out.length() > 0) out else null
                }
            }
            out
        } catch (t: Throwable) {
            null
        }
    }

    // ── 输入视图 ────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        val theme = currentTheme()
        inputRoot = FrameLayout(this)
        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.panelBg)
        }

        // 粘贴气泡（§5.8：新剪贴板内容 + 可编辑焦点 → 候选栏上方，10s 自动消失）
        pasteBubble = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onBubbleTapped() }
        }

        candidateBar = CandidateBar(this).apply {
            this.theme = theme
            // v1.5：候选字号来自设置界面
            textSizeSp = GkPrefs.candidateTextSize(this@GkImeService)
            onPick = { idx -> onCandidatePicked(idx) }
            onComposingCommit = { commitComposing() }
            onComposingClear = { engine?.reset(); refreshCandidates() }
            // v1.2 手动分词：在首个未锚定音节边界插入分隔符（锚定持久）
            onSplit = {
                if (engine?.insertManualSeparator() == true) refreshCandidates()
            }
        }

        toolbar = ToolbarView(this).apply {
            this.theme = theme
            onTool = { tool -> onToolbarTool(tool) }
        }

        panelHost = FrameLayout(this)
        keyboardView = KeyboardView(this).apply {
            this.theme = theme
            keyboard = KeyboardDefs.pinyin26()
            // v1.5.8：字母键上滑标点开关
            swipePunctEnabled = GkPrefs.swipePunct(this@GkImeService)
            // v1.5：键盘高度缩放来自设置界面
            keyHeightScale = GkPrefs.keyHeightScale(this@GkImeService)
            onKey = { code -> onKeyDownFromUi(code) }
            // v1.3 长按空格语音：按住说话、松开停止（微信式）
            onSpaceHoldStart = { onSpaceHoldVoice(true) }
            // v1.5.5：回车长按 + 上滑 → 清空光标前所有文本
            onEnterSwipeClear = { clearTextBeforeCursor() }
            onSpaceHoldEnd = { onSpaceHoldVoice(false) }
        }
        panelHost.addView(keyboardView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // v1.6.5：四个功能面板不再在此构造（首次切到对应面板时惰性创建，
        // 见 ensureXxxPanel），弹出路径上只建键盘 + 候选栏 + 工具栏。

        // 悬浮模式下用 spacer 保持 IME 窗口高度（浮动键盘在窗口内自由移动）
        floatingSpacer = View(this).apply { visibility = View.GONE }

        floatingLayer = FloatingContainer(this).apply {
            visibility = View.GONE
            onRestore = { toggleFloating(off = true) }
        }

        mainColumn.addView(pasteBubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainColumn.addView(candidateBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainColumn.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainColumn.addView(panelHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainColumn.addView(floatingSpacer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0))
        inputRoot.addView(mainColumn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inputRoot.addView(floatingLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // v1.6.5：语音流式文本不再用独立 HUD View（见 showVoiceHud 注释）

        // 修复：startInput 可能早于本回调执行过（已切换布局/候选状态），
        // 视图创建完成后按当前布局同步键盘，避免显示错误的初始布局。
        refreshKeyboard()
        refreshCandidates()
        // v1.3：视图重建时重置路线 B 控制器（旧视图树已失效）
        overlayCtl = null
        // v1.6.0：弹出瞬时内存快照（开发者日志可见）
        com.glasskey.ime.dev.GkMemMonitor.logSnapshot("inputViewCreated", this)
        return inputRoot
    }

    private fun hostParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, 0, 0, 0)
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun currentTheme(): Theme {
        val mode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        // v1.5：主题色来自设置界面（水墨 5 色可选）
        return Theme.of(mode == android.content.res.Configuration.UI_MODE_NIGHT_YES,
            GkPrefs.accentIndex(this))
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // v1.7.4 敏感字段检测（对齐主流大厂做法：搜狗"私密字段屏蔽学习" /
        // Gboard incognito）——密码框、电话号码等不学习、不进预测上下文
        sensitiveField = isSensitiveEditor(info)
        engine?.reset()
        numpadMode = false   // v1.7.1：新输入会话回到主键盘
        capMode = LatinCapHelper.Mode.OFF
        currentEditorLayout = EditorMapper.map(info, EditorMapper.LAYOUT_PINYIN)
        // 输入类型 → 初始布局（§5.1.3）
        layoutMode = when (currentEditorLayout) {
            EditorMapper.LAYOUT_NUMBER, EditorMapper.LAYOUT_LATIN -> GkLayout.LATIN
            else -> GkLayout.PINYIN_26
        }
        engine?.setLayout(layoutMode)
        refreshKeyboard()
        refreshCandidates()
    }

    /**
     * v1.7.4：敏感输入框判定。密码（文本/数字/网页）、电话号码、SIM PIN 类
     * 字段的内容一律不进入任何学习通道（bigram / 实体 / 热词 / 语音纠错记忆），
     * 也不作为下文预测的上下文——避免用户名密码等私密内容残留在学习数据中。
     */
    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        val it = info?.inputType ?: return false
        val cls = it and android.text.InputType.TYPE_MASK_CLASS
        val vari = it and android.text.InputType.TYPE_MASK_VARIATION
        return when (cls) {
            android.text.InputType.TYPE_CLASS_PHONE -> true
            android.text.InputType.TYPE_CLASS_TEXT -> vari ==
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                vari == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                vari == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER ->
                vari == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** v1.7.4：当前焦点输入框是否敏感字段（密码/电话等，屏蔽全部学习） */
    private var sensitiveField = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // §5.8 捕获：键盘窗口获焦时读取剪贴板（无需权限声明）
        captureClipboard()
        // v1.5.8 悬浮模式全局记忆：开启过悬浮（不按应用区分），任意输入框唤起
        // 键盘自动回到悬浮模式，并恢复上次拖动的位置（控制器内读取持久化坐标）
        if (!floating && !restarting &&
            GkPrefs.floatingStickyOn(this) && Settings.canDrawOverlays(this) &&
            panelMode == Panel.KEYBOARD
        ) {
            toggleFloating()
        }
        // v1.6.0：设置页改完上滑标点开关 → 回到输入框立即生效（无需重建键盘）
        if (::keyboardView.isInitialized) {
            keyboardView.swipePunctEnabled = GkPrefs.swipePunct(this)
            keyboardView.invalidate()
        }
        // v1.6.0：每次弹出的内存快照（低频，弹出时一条）
        com.glasskey.ime.dev.GkMemMonitor.logSnapshot("inputViewShown", this)
    }

    override fun onFinishInput() {
        engine?.sessionEnd()  // 会话结束衰减（§4.5）
        super.onFinishInput()
        // 修复（v1.5.2，真机反馈"悬浮窗遗留原地"）：输入会话结束时若仍处于
        // 悬浮状态，完整收回悬浮窗并还原视图树（原条件 `&& !floating` 恰好
        // 把真正悬浮的情况排除，导致空壳窗口残留原地）
        if (floating) toggleFloating(off = true)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // 键盘窗口整体隐藏时同样收回悬浮（防输入框降下后悬浮窗滞留）
        if (floating) toggleFloating(off = true)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false  // 不占全屏（横屏可选）

    // ── 工具栏与面板切换 ────────────────────────────────────────

    private fun onToolbarTool(tool: ToolbarView.Tool) {
        when (tool) {
            ToolbarView.Tool.CLIP ->
                switchPanel(if (panelMode == Panel.CLIP) Panel.KEYBOARD else Panel.CLIP)
            ToolbarView.Tool.HWR ->
                switchPanel(if (panelMode == Panel.HWR) Panel.KEYBOARD else Panel.HWR)
            ToolbarView.Tool.VOICE ->
                switchPanel(if (panelMode == Panel.VOICE) Panel.KEYBOARD else Panel.VOICE)
            ToolbarView.Tool.SYMBOL ->
                switchPanel(if (panelMode == Panel.SYMBOL) Panel.KEYBOARD else Panel.SYMBOL)
            ToolbarView.Tool.FLOAT -> {
                if (floating) {
                    // 用户手动关闭悬浮 → 清除全局记忆
                    GkPrefs.setFloatingStickyOn(this, false)
                    toggleFloating(off = true)
                } else {
                    toggleFloating()
                    if (floating) GkPrefs.setFloatingStickyOn(this, true)
                }
            }
            ToolbarView.Tool.HIDE ->
                // v1.5.5：悬浮模式收起——先撤悬浮窗再收 IME（不做可见的还原切换，
                // 修复"先退回正常键盘再收起"的两段式不丝滑）
                if (floating) collapseFloatingAndHide() else requestHideSelf(0)
        }
    }

    /** 路线 B 悬浮窗权限引导：说明 + 用户主动"去授权"（IME 服务内弹窗需 try 保护 token） */
    private fun showOverlayPermissionGuide() {
        val msg = "悬浮键盘（真悬浮窗）需要「显示在其他应用上层」权限：\n\n" +
            "1. 点「去授权」打开系统设置\n" +
            "2. 在列表中找到 GlassKey 并打开开关\n" +
            "3. 返回键盘后再点一次工具栏「悬浮」\n\n" +
            "暂不授权可使用窗口内悬浮（路线 A）。"
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("悬浮窗权限引导")
                .setMessage(msg)
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "无法打开授权页，请到系统设置→应用→GlassKey 手动开启", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("暂不，用窗口内悬浮") { _, _ ->
                    Toast.makeText(this, "已使用窗口内悬浮模式", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (_: Throwable) {
            // 个别 ROM 禁止 IME 上下文弹窗：降级为 Toast 提示，不跳转
            Toast.makeText(this, "路线 B 需在系统设置中为 GlassKey 开启悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchPanel(target: Panel) {
        if (!::panelHost.isInitialized) return
        panelMode = target
        if (voicePanel != null && target != Panel.VOICE) voicePanel?.onPanelHidden()
        // 修复（v1.5.2：真机"给了权限点悬浮没反应"）：
        // 悬浮路线下键盘视图已摘出 panelHost（路线 A 挂 floatingLayer / 路线 B 挂系统
        // overlay 窗口），都不在本容器 —— 原条件 `&& !floating` 会把悬浮中的键盘视图
        // 置 GONE，造成路线 A 悬浮层空白、看起来"悬浮没启动"。悬浮时视图不在本树，
        // 直接按面板可见性设置即可。
        keyboardView.visibility =
            if (target == Panel.KEYBOARD) View.VISIBLE else View.GONE
        // v1.6.5：目标面板首次使用时才构造（惰性创建，压缩键盘弹出耗时）
        when (target) {
            Panel.CLIP -> ensureClipPanel()
            Panel.HWR -> ensureHwrPanel()
            Panel.VOICE -> ensureVoicePanel()
            Panel.SYMBOL -> ensureSymbolPanel()
            Panel.KEYBOARD -> {}
        }
        clipPanel?.visibility = if (target == Panel.CLIP) View.VISIBLE else View.GONE
        hwrPanel?.visibility = if (target == Panel.HWR) View.VISIBLE else View.GONE
        voicePanel?.visibility = if (target == Panel.VOICE) View.VISIBLE else View.GONE
        symbolPanel?.visibility = if (target == Panel.SYMBOL) View.VISIBLE else View.GONE
        if (target == Panel.CLIP) {
            val cdb = clipDb
            if (cdb != null) clipPanel?.attach(cdb, currentTheme())
        }
        if (target == Panel.VOICE) {
            // 修复（真机反馈：授权后语音面板永远"去授权"）：授权返回再入面板时
            // 重刷权限/模型门态（原实现仅 init 时调用一次，状态永不更新）
            voicePanel?.refreshGate()
        }
        if (target != Panel.HWR) {
            hwrCandidates = emptyList()   // 离开手写清空手写候选通道
        }
        refreshCandidates()
    }

    /** 悬浮键盘切换（§5.7：路线 A 窗口内浮动；路线 B 真悬浮窗） */
    private fun toggleFloating(off: Boolean = false) {        if (!::panelHost.isInitialized || !::keyboardView.isInitialized) return
        if (off || floating) {
            // —— 还原固定面板 ——
            // v1.6.0：还原 IME 主窗口背景（悬浮期间被置透明，见下方 routeB on 分支）
            try {
                imeWindowBg?.let { bg -> window.window?.decorView?.background = bg }
            } catch (_: Throwable) {}
            imeWindowBg = null
            if (overlayCtl?.isShowing == true) {
                // 路线 B：content（mainColumn）还回 inputRoot
                overlayCtl?.hide()
                overlayCtl = null
                floatingSpacer.visibility = View.GONE
            } else {
                // 路线 A：键盘视图从窗口内浮动容器摘回 panelHost
                floatingLayer.detachKeyboard()?.let { v ->
                    (v.parent as? ViewGroup)?.removeView(v)
                    panelHost.addView(v, 0, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                floatingLayer.visibility = View.GONE
                floatingSpacer.visibility = View.GONE
            }
            floating = false
            toolbar.floatingActive = false
            keyboardView.visibility = if (panelMode == Panel.KEYBOARD) View.VISIBLE else View.GONE
        } else {
            // 路线 B（真悬浮窗）：v1.5.2 起 —— 系统悬浮窗权限已授予即直接走路线 B，
            // 不再要求额外打开设置里的"真悬浮窗"开关（用户已授权却仍走窗口内
            // 悬浮是本次真机反馈的核心困惑）；未授权时：开关开启 → 弹授权引导；
            // 开关未开 → 静默走路线 A。
            if (!Settings.canDrawOverlays(this)) {
                if (GkPrefs.floatingRouteB(this)) {
                    showOverlayPermissionGuide()
                    return   // 本次不进入悬浮；授权返回后用户再点一次"悬浮"即可
                }
            } else {
                val ctl = overlayCtl ?: FloatingOverlayController(this, mainColumn)
                    .also { overlayCtl = it }
                if (ctl.show()) {
                    floating = true
                    toolbar.floatingActive = true
                    com.glasskey.ime.dev.GkLogger.log("float", "routeB on")
                    // IME 主窗口 1dp 占位：几乎不可见但会话不中断
                    floatingSpacer.layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    floatingSpacer.visibility = View.VISIBLE
                    switchPanel(Panel.KEYBOARD)
                    // v1.6.0 修复"悬浮模式屏幕正下方白条"：主窗口内容被摘走后，
                    // 残存窗口区域渲染的是主题 windowBackground（近白 #F7F8FC）。
                    // 悬浮期间把 IME 窗口背景置为透明，退出悬浮时还原。
                    try {
                        imeWindowBg = window.window?.decorView?.background
                        window.window?.setBackgroundDrawableResource(
                            android.R.color.transparent)
                        inputRoot.requestLayout()
                    } catch (_: Throwable) {}
                    Toast.makeText(this, "真悬浮键盘已开启（可拖动顶部条移动）", Toast.LENGTH_SHORT).show()
                    return
                }
                Toast.makeText(this, "悬浮窗创建失败，已降级为窗口内悬浮", Toast.LENGTH_SHORT).show()
            }
            // 路线 A：窗口内浮动容器
            val kbHeight = keyboardView.height
            panelHost.removeView(keyboardView)
            keyboardView.visibility = View.VISIBLE
            floatingLayer.attachKeyboard(keyboardView)
            floatingLayer.setTheme(currentTheme())
            floatingLayer.visibility = View.VISIBLE
            floatingSpacer.visibility = View.VISIBLE
            floatingSpacer.layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (kbHeight > 0) kbHeight else dp(220))
            floating = true
            toolbar.floatingActive = true
            switchPanel(Panel.KEYBOARD)
        }
    }

    // —— 长按空格语音（v1.3）：按住说话、松开停止 ——
    /** v1.5.5：清空光标前的所有文本（回车长按上滑手势）。
     *  deleteSurroundingText 一次上限较大值，超长分批删；兼容性优于 setText。 */
    private fun clearTextBeforeCursor() {
        val ic = currentInputConnection ?: return
        var removed = 0
        while (removed < 100_000) {
            val batch = 4096
            val before = try {
                ic.getTextBeforeCursor(batch + 1, 0)?.length ?: 0
            } catch (_: Throwable) { break }
            if (before == 0) break
            ic.deleteSurroundingText(before, 0)
            removed += before
            if (before < batch + 1) break   // 已删到行首/边界
        }
        Toast.makeText(this, "已清空光标前文本", Toast.LENGTH_SHORT).show()
        engine?.reset()
        refreshCandidates()
    }

    /** v1.5.5：悬浮模式一键收起——先撤悬浮窗，再收起 IME，不经过可见的还原态 */
    private fun collapseFloatingAndHide() {
        if (overlayCtl?.isShowing == true) {
            // 路线 B：直接撤系统悬浮窗（不还原视图树，避免闪一下正常键盘）
            overlayCtl?.hide()
            overlayCtl = null
            floating = false
            toolbar.floatingActive = false
            if (::floatingSpacer.isInitialized) floatingSpacer.visibility = View.GONE
        } else {
            // 路线 A：窗口内浮动，随 IME 窗口一起收起，正常还原
            toggleFloating(off = true)
        }
        requestHideSelf(0)
    }

    private fun onSpaceHoldVoice(start: Boolean) {
        // v1.6.5：长按空格语音不再依赖语音面板已创建——按需惰性构造面板
        //（构造为纯视图操作，模型预热仍在面板内部后台线程）
        if (!::panelHost.isInitialized) return
        val vp = ensureVoicePanel()
        if (start) {
            val ok = vp.quickStart { partial -> showVoiceHud(partial) }
            if (!ok) {
                Toast.makeText(this, "需要麦克风权限：点工具栏「语音」按引导授权", Toast.LENGTH_LONG).show()
                return
            }
            showVoiceHud("聆听中…松开结束")
        } else {
            vp.quickStop()
            hideVoiceHud()
        }
    }

    // v1.6.5：语音流式识别文本直接写候选栏合成层（上层"原字母"行）——
    // 移除旧独立 HUD 覆盖 View（挂悬浮窗根部、WRAP_CONTENT，每出文本新增
    // 一层把键盘整体顶上去，即真机"悬浮时往上跳、多出一个 UI"的根因）。
    // 合成层高度在 v1.6.4 已恒定占位，零布局变化，普通/悬浮模式均不跳屏。
    private var voiceHudActive = false

    private fun showVoiceHud(text: String) {
        if (!::candidateBar.isInitialized) return
        voiceHudActive = true
        candidateBar.setComposing(text)
    }

    private fun hideVoiceHud() {
        if (!voiceHudActive) return
        voiceHudActive = false
        refreshCandidates()
    }

    // ── 剪贴板（§5.8）─────────────────────────────────────────

    private fun captureClipboard() {
        if (!GkPrefs.clipboardEnabled(this)) return
        if (!::pasteBubble.isInitialized) return
        val clip = ClipboardModule.readPrimary(this) ?: return
        val (text, sensitive) = clip
        if (sensitive) return
        // 修复：原仅内存去重，服务重建后同一条内容重复弹（真机反馈：每次切换输入法都弹）。
        // 现持久化"已展示"记录到 SharedPreferences：同一条内容只弹一次气泡；
        // 外加时间阈值双保险（同一内容 90s 内绝不重复弹）。
        val now = System.currentTimeMillis()
        val (seenText, seenAt) = GkPrefs.clipSeen(this)
        if (text == lastClipText) return
        if (text == seenText && now - seenAt < 90_000L) return
        lastClipText = text
        GkPrefs.markClipSeen(this, text, now)
        Thread { clipDb?.insert(text, sensitive) }.start()
        // 粘贴气泡 + 快捷动作（验证码一键上屏，§5.8）
        val smart = ClipboardModule.smartClip(text)
        bubbleText = smart?.token ?: text
        pasteBubble.text = when (smart) {
            null -> "粘贴：${text.replace("\n", " ").take(24)}"
            else -> "⌨ ${smart.label}（点按上屏）"
        }
        pasteBubble.visibility = View.VISIBLE
        bubbleDismiss?.let { uiHandler.removeCallbacks(it) }
        bubbleDismiss = Runnable { pasteBubble.visibility = View.GONE }.also {
            uiHandler.postDelayed(it, BUBBLE_TIMEOUT_MS)   // 10s 自动消失
        }
    }

    private var bubbleText: String = ""

    private fun onBubbleTapped() {
        commitText(bubbleText)
        pasteBubble.visibility = View.GONE
    }

    // ── 击键分发 ────────────────────────────────────────────────

    private fun onKeyDownFromUi(code: Int) {
        val e = engine ?: return
        // v1.5.9：开发者日志——每次击键（码/模式/合成串/候选数），排查输入链路必备
        // v1.6.3：退格长按降噪——连续退格每 10 条记 1 条（真机反馈日志被刷屏）
        if (com.glasskey.ime.dev.GkLogger.enabled) {
            val skipLog = if (code == KeyboardDefs.CODE_BACKSPACE) {
                ++bsLogTick % 10 != 1
            } else {
                bsLogTick = 0
                false
            }
            if (!skipLog) {
                com.glasskey.ime.dev.GkLogger.log("key",
                    "code=$code mode=$layoutMode input='${e.input()}' cands=${e.candidateCount()}")
            }
        }
        when {
            // v1.7.1：数字小键盘模式——数字/句点直接上屏（"数字打不出来"修复：
            // 原实现数字 2-9 全部喂给拼音引擎、1 无人处理，任何模式下都打不出数字）；
            // 退格走下方通用路径
            numpadMode && code != KeyboardDefs.CODE_BACKSPACE -> {
                when (code) {
                    KeyboardDefs.CODE_NUMPAD -> {
                        numpadMode = false
                        refreshKeyboard()
                        refreshCandidates()
                    }
                    in '0'.code..'9'.code -> commitText(code.toChar().toString())
                    KeyboardDefs.CODE_SYMBOL_PERIOD -> commitText(".")
                }
                return
            }
            code == KeyboardDefs.CODE_BACKSPACE -> {
                cancelContAutoCommit()  // v1.4：退格取消待上屏判定
                if (e.backspace() == 0) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    // v1.5.8 修复：主键盘退格同样计入语音修正窗口
                    //（原实现只在手写面板退格回调计数 → 语音学习永远无记录）
                    noteVoiceWindowBackspace()
                }
                refreshCandidates()
            }
            code == KeyboardDefs.CODE_LAYOUT_SWITCH -> {
                layoutMode =
                    if (layoutMode == GkLayout.LATIN) GkLayout.PINYIN_26 else GkLayout.LATIN
                e.setLayout(layoutMode)
                refreshKeyboard()
                refreshCandidates()
                // 中英状态可见性（真机反馈）：切换即时提示
                Toast.makeText(
                    this,
                    if (layoutMode == GkLayout.LATIN) "已切换：英文" else "已切换：中文拼音",
                    Toast.LENGTH_SHORT
                ).show()
            }
            code == KeyboardDefs.CODE_MODE_SWITCH -> {
                layoutMode =
                    if (layoutMode == GkLayout.PINYIN_9) GkLayout.PINYIN_26 else GkLayout.PINYIN_9
                e.setLayout(layoutMode)
                refreshKeyboard()
                refreshCandidates()
                Toast.makeText(
                    this,
                    if (layoutMode == GkLayout.PINYIN_9) "已切换：九键拼音" else "已切换：26 键拼音",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // v1.7.1：进入数字小键盘（NumPad 风格独立布局，再按「返回」切回）
            code == KeyboardDefs.CODE_NUMPAD -> {
                cancelContAutoCommit()
                if (e.input().isNotEmpty()) commitComposing()
                numpadMode = true
                refreshKeyboard()
                refreshCandidates()
            }
            code == KeyboardDefs.CODE_SHIFT -> {
                capMode = LatinCapHelper.shift(capMode)
                refreshKeyboard()
            }
            code == KeyboardDefs.CODE_ENTER -> {
                cancelContAutoCommit()
                handleEnter()
            }
            code == ' '.code -> {
                cancelContAutoCommit()
                handleSpace()
            }
            code == KeyboardDefs.CODE_SYMBOL_COMMA ||
                code == KeyboardDefs.CODE_SYMBOL_PERIOD -> {
                // v1.5.6：中文模式下标点自动转全角"，"/"。"（原实现恒上英文标点）；
                // 拼音合成中有内容时先上屏首选，再上标点（与主流 IME 一致）
                val ch = code.toChar()
                val punct = if (layoutMode == GkLayout.LATIN) ch.toString()
                    else if (ch == ',') "，" else "。"
                cancelContAutoCommit()
                if (layoutMode != GkLayout.LATIN && e.input().isNotEmpty()) commitComposing()
                commitText(punct)
            }
            code in 'a'.code..'z'.code -> handleLetter(code)
            // v1.5.8：字母键上滑输出的标点（！？、～…《》等）直接上屏
            KeyboardDefs.SWIPE_PUNCT.values.any { it.code == code } -> {
                val ch = KeyboardDefs.SWIPE_PUNCT.values.first { it.code == code }
                cancelContAutoCommit()
                if (layoutMode != GkLayout.LATIN && e.input().isNotEmpty()) commitComposing()
                commitText(ch.toString())
            }
            code in '2'.code..'9'.code -> {
                e.processKey(code)
                refreshCandidates()
            }
            code == KeyboardDefs.CODE_SEP_APOSTROPHE -> {
                e.processKey(code)
                refreshCandidates()
            }
        }
    }

    private fun handleLetter(code: Int) {
        val e = engine ?: return
        if (layoutMode == GkLayout.LATIN && capMode != LatinCapHelper.Mode.OFF) {
            // 上档字母直接上屏，不进合成（真实 IME 惯例）
            commitText(LatinCapHelper.apply(code.toChar(), true).toString())
            capMode = LatinCapHelper.afterLetter(capMode)
            refreshKeyboard()
            return
        }
        e.processKey(code)
        refreshCandidates()
        // v1.4 连续拼音：引擎无候选（首字母/前缀/混合形态）时安排延迟判定，
        // 停顿 [CONT_DEBOUNCE_MS] 无新键 → 高置信结果直接上屏（可配置关）
        if (layoutMode == GkLayout.PINYIN_26 && e.candidateCount() == 0 &&
            e.input().length >= 2 && e.input().all { it in 'a'..'z' }
        ) {
            scheduleContAutoCommit()
        } else {
            cancelContAutoCommit()
        }
    }

    // ── v1.4 连续拼音解析 ─────────────────────────────────────

    /** 用户词/历史词快照（后台装载，主线程只读） */
    private fun contLexicon(): ContinuationResolver.Lexicon {
        ensureContLexiconLoaded()
        return object : ContinuationResolver.Lexicon {
            override fun wordsFor(pattern: String): List<Triple<String, String, Int>> {
                val patTokens = pattern.split(" ").filter { it.isNotEmpty() }
                if (patTokens.isEmpty()) return emptyList()
                // v1.5.8：按音节数索引（6 万词词库接入后全表扫描不可行）
                // v1.5.9 修复：全量匹配后按词频排序取前 12（原"凑满即停"按文件序
                // 截断，高频词进不了候选 → 组句命中率崩塌）
                val bucket = contLexByCount[patTokens.size] ?: return emptyList()
                val out = ArrayList<Triple<String, String, Int>>(16)
                // 首 token 匹配器：FULL=精确 / xxx*=前缀（v1.5.9 修复：原 dropLast(1)
                // 把 "kai" 削成 "ka" 且循环跳过首 token → 首音节根本没参与匹配）
                val t0 = patTokens[0]
                val t0IsPrefix = t0.endsWith("*") && t0.length > 1
                val t0Key = if (t0IsPrefix) t0.dropLast(1) else t0
                for ((word, pyTokens, freq) in bucket) {
                    val p0 = pyTokens[0]
                    val ok0 = if (t0IsPrefix) p0.startsWith(t0Key) else p0 == t0Key
                    if (!ok0) continue
                    var ok = true
                    for (k in 1 until patTokens.size) {
                        val t = patTokens[k]
                        val p = pyTokens[k]
                        val match = when {
                            t.endsWith("*") && t.length > 1 -> p.startsWith(t.dropLast(1))
                            else -> p == t
                        }
                        if (!match) { ok = false; break }
                    }
                    if (ok) out.add(Triple(word, pyTokens.joinToString(" "), freq))
                }
                out.sortByDescending { it.third }
                return out.take(12)
            }

            override fun historyTop(pattern: String): String? {
                val norm = pattern.replace(" ", "")
                return contLexiconSnapshot
                    .filter { it.second.replace(" ", "").replace("'", "") == norm }
                    .maxByOrNull { it.third }?.first
            }

            // v1.6.0：词对转移分——语料 bigram（内置 assets + 用户订阅导入）
            override fun pairBoost(prev: String, next: String): Double {
                ensureBigramsLoaded()
                val c = bigramMap[prev]?.get(next) ?: return 0.0
                // log 压缩：c=3→0.35, c=10→0.60, c=50→0.98，c≥50 封顶 1.0
                return kotlin.math.ln(1.0 + c.toDouble()).coerceAtMost(4.0) / 4.0
            }
        }
    }

    // v1.6.0 语料词对表（内置 assets 首次部署 + 订阅导入覆盖）
    // v1.6.2：后台加载——打字热路径只读 @Volatile 表，未就绪时 pairBoost=0
    @Volatile
    private var bigramMap: Map<String, Map<String, Int>> = emptyMap()
    @Volatile
    private var bigramRev = -1

    private fun ensureBigramsLoaded() {
        val rev = com.glasskey.app.LexiconStore.revision
        if (bigramRev == rev) return
        synchronized(this) {
            if (bigramRev == rev) return
            val targetRev = rev
            Thread {
                val m = HashMap<String, HashMap<String, Int>>(4096)
                fun load(f: java.io.File?) {
                    if (f == null || !f.isFile) return
                    try {
                        f.useLines { lines ->
                            for (ln in lines) {
                                val t = ln.trim().split(' ')
                                if (t.size < 3) continue
                                val c = t[2].toIntOrNull() ?: continue
                                m.getOrPut(t[0]) { HashMap() }[t[1]] = c
                            }
                        }
                    } catch (_: Throwable) { }
                }
                load(com.glasskey.app.LexiconStore.bigramFile(this))
                load(com.glasskey.app.LexiconStore.importedBigramFile(this))  // 导入数据优先（后写覆盖）
                // v1.6.8：订阅词对文件（按订阅顺序，最后写入优先）
                for (f in com.glasskey.app.LexiconStore.subscriptionFiles(
                    this@GkImeService, com.glasskey.app.LexiconStore.Kind.BIGRAM)) load(f)
                bigramMap = m
                bigramRev = targetRev
                com.glasskey.ime.dev.GkLogger.log("bigram", "loaded ${m.size} heads rev=$targetRev")
            }.apply { name = "gk-bigram-load"; isDaemon = true }.start()
            // 立即占位：后台加载完成前本轮 rev 视为已处理（pairBoost 暂 0）
            bigramRev = targetRev
        }
    }

    /** v1.5.8：连续拼音词库（音节数 → 词条），wordsFor O(bucket) 查询 */
    private var contLexByCount: Map<Int, List<Triple<String, List<String>, Int>>> = emptyMap()

    private fun ensureContLexiconLoaded() {
        // v1.6.0：订阅导入后 revision 变化 → 热重载（含导入词库合并）
        if (contLexiconLoaded && contLexRev == com.glasskey.app.LexiconStore.revision) return
        contLexiconLoaded = true
        contLexRev = com.glasskey.app.LexiconStore.revision
        contCache.clear()  // 词库/词对更新 → 旧解析缓存作废
        Thread {
            val entries = ArrayList<Triple<String, String, Int>>()
            try { db?.all().orEmpty().let { u ->
                entries.addAll(u.filter { it.pinyin.isNotBlank() }
                    .map { Triple(it.word, it.pinyin, it.freq + 100_000) })  // 用户词权重最高
            } } catch (_: Throwable) { }
            // v1.5.8：内置 6 万高频词接入连续拼音/长句组合（"nihao" 无空格 → 贪心切分）
            fun loadLexFile(f: java.io.File?) {
                if (f == null || !f.isFile) return
                try {
                    f.useLines { lines ->
                        for (ln in lines) {
                            val t = ln.split('\t')
                            if (t.size < 2) continue
                            val word = t[0].trim()
                            val freq = t.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
                            if (word.isEmpty() || word.length > 6) continue
                            val syls = com.glasskey.ime.pinyin.Syllables.greedySplit(t[1].trim())
                                ?: continue
                            entries.add(Triple(word, syls.joinToString(" "), freq))
                        }
                    }
                } catch (_: Throwable) { }
            }
            loadLexFile(File(filesDir, "dict/voice_lexicon.tsv"))
            // v1.6.0：订阅导入的词库（放内置之后，同词可被覆盖为导入词频）
            loadLexFile(com.glasskey.app.LexiconStore.importedLexiconFile(this))
            // v1.6.8：链接订阅的词库文件（按订阅顺序，后写覆盖同词词频）
            for (f in com.glasskey.app.LexiconStore.subscriptionFiles(
                this@GkImeService, com.glasskey.app.LexiconStore.Kind.LEXICON)) loadLexFile(f)
            // v1.6.3：短期热词表（历史输入分词保存，3 天热处理，过期自动丢弃）
            try {
                for ((hw, hf) in com.glasskey.ime.predict.RecentHotWords.all()) {
                    val py = contLexiconSnapshot.firstOrNull { it.first == hw }?.second
                    if (py != null) entries.add(Triple(hw, py, hf))
                }
            } catch (_: Throwable) { }
            contLexiconSnapshot = entries
            contLexWordSet = entries.mapTo(HashSet(entries.size)) { it.first }
            contLexByCount = entries.groupBy(
                { it.second.split(" ", "'").count { it.isNotEmpty() } }) { e ->
                Triple(e.first, e.second.split(" ", "'").filter { it.isNotEmpty() }, e.third)
            }
        }.apply { name = "gk-cont-lexicon"; isDaemon = true }.start()
    }

    /** v1.6.0：已装载词库对应的导入版本（订阅导入后热重载） */
    private var contLexRev = -1
    /** v1.6.3：词库快照词集（短期热词分词匹配用） */
    @Volatile
    private var contLexWordSet: Set<String> = emptySet()

    private fun resolver(): ContinuationResolver {
        return contResolver ?: ContinuationResolver(
            contLexicon(),
            ContinuationResolver.Config(
                candidateLimit = GkPrefs.candidateLimit(this),
                highConfidence = GkPrefs.continuationHighConfidence(this),
            ),
        ).also { contResolver = it }
    }

    /**
     * 解析连续输入：带 LRU 缓存（同串直接命中）。
     * @return 解析结果；null = 不适用（含非字母等，回退现有交互）
     */
    private fun resolveContinuation(raw: String): ContinuationResolver.Result? {
        if (raw.length < 2 || raw.any { it !in 'a'..'z' }) return null
        contCache[raw]?.let { return it }
        val r = try { resolver().resolve(raw) } catch (t: Throwable) {
            android.util.Log.w("GkCont", "resolve failed", t)
            com.glasskey.ime.dev.GkLogger.logThrowable("cont", "resolve failed", t)
            null
        } ?: return null
        contCache[raw] = r
        return r
    }

    /** 停顿判定（防抖）：350ms 无新键才允许直接上屏，打字过程只展示候选 */
    private fun scheduleContAutoCommit() {
        cancelContAutoCommit()
        val r = Runnable { contAutoCommitCheck() }
        contPendingCommit = r
        contUiHandler.postDelayed(r, CONT_DEBOUNCE_MS)
    }

    private fun cancelContAutoCommit() {
        contPendingCommit?.let { contUiHandler.removeCallbacks(it) }
        contPendingCommit = null
    }

    private fun contAutoCommitCheck() {
        contPendingCommit = null
        val e = engine ?: return
        val raw = e.input()
        if (raw.length < 2 || raw.any { it !in 'a'..'z' }) return
        val r = resolveContinuation(raw) ?: return
        val top = r.candidates.firstOrNull() ?: return
        val auto = GkPrefs.continuationAutoCommit(this)
        val always = GkPrefs.continuationAlwaysCommit(this)
        if (!auto && !always) {
            android.util.Log.i("GkCont", "auto-commit off, keep candidates raw=$raw")
            return
        }
        if (!r.highConfidence && !always) {
            // 低置信保护：不直接上屏，保留候选/确认交互（现有机制）
            android.util.Log.i(
                "GkCont", "low confidence ${r.confidence} < threshold, fallback to candidates raw=$raw"
            )
            return
        }
        android.util.Log.i(
            "GkCont",
            "commit '${top}' raw=$raw seg=${r.segmentation} conf=${r.confidence}${if (always) " [always]" else ""}"
        )
        commitText(top)
        e.reset()
        refreshCandidates()
    }

    /** 语音后处理词库适配：用户词库（词+拼音+频次）作热词与同音纠正源；
     *  v1.5.3：语音学习记忆条目（ASR 原词→用户修正词，按热度降序）为最高优先热词 */
    private fun voiceLexicon(): VoicePostProcessor.Lexicon {
        return object : VoicePostProcessor.Lexicon {
            override val hotWords: List<Pair<String, String>> by lazy {
                val learned = com.glasskey.app.GkPrefs.voiceLearnEntries(this@GkImeService)
                    .map { it.from to it.to }
                val userDict = try {
                    db?.all().orEmpty().map { it.pinyin to it.word }
                } catch (_: Throwable) { emptyList() }
                learned + userDict
            }
            override val words: List<Pair<String, String>> by lazy {
                try { db?.all().orEmpty().map { it.word to it.pinyin } } catch (_: Throwable) { emptyList() }
            }
        }
    }

    private fun handleSpace() {
        val e = engine ?: return
        if (layoutMode == GkLayout.LATIN || layoutMode == GkLayout.PINYIN_9) {
            if (layoutMode == GkLayout.LATIN) {
                commitText(" ")
                return
            }
            // T9：空格 = 分词推进/提交首选
        }
        if (e.candidateCount() > 0) {
            pickAndCommit(0)
        } else {
            commitText(" ")
        }
    }

    private fun handleEnter() {
        val action = EditorMapper.enterAction(currentInputEditorInfo)
        // 修复（真机反馈：点确认键输入法直接收起）：IME_ACTION_DONE 的"完成"语义会触发
        // 系统/App 侧收起 IME。SEND（发送）/SEARCH（搜索）保留 action（聊天/搜索预期行为），
        // 其余编辑框退化为换行键，保住键盘不收起。
        if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_SEARCH) {
            val editor = currentInputConnection
            if (editor?.performEditorAction(action) == true) {
                engine?.reset()
                refreshCandidates()
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        engine?.reset()
        refreshCandidates()
    }

    // ── 候选 ────────────────────────────────────────────────────

    private fun onCandidatePicked(index: Int) {
        // v1.7.2：兜底通道候选（不在引擎里）按展示列表直接提交。
        // 修复：悬浮模式打多字词时"只有第一个候选能点"——原实现一律走
        // e.commitDetail(index)，引擎无该下标候选 → 取空 → 无反应。
        if (!candsFromEngine) {
            val text = shownCands.getOrNull(index) ?: return
            cancelContAutoCommit()
            commitText(text)
            engine?.reset()
            refreshKeyboard()
            refreshCandidates()
            return
        }
        // v1.5.8 下文预测通道：合成区为空时的预测词直接上屏
        if (predictCands.isNotEmpty() && engine?.input()?.isEmpty() != false) {
            predictCands.getOrNull(index)?.let {
                commitText(it)
                refreshCandidates()
            }
            return
        }
        // 手写候选通道：点击上屏识别字并复位画布（v1.5.3：不再自动上屏）
        if (panelMode == Panel.HWR && hwrCandidates.isNotEmpty()) {
            hwrCandidates.getOrNull(index)?.let { hwrPanel?.commitCandidate(it) }
            return
        }
        // 英文模式候选首位为"原文上屏"选项
        if (layoutMode == GkLayout.LATIN && engine?.input()?.isNotEmpty() == true) {
            if (index == 0) {
                commitComposing()
                return
            }
            pickAndCommit(index - 1)
            return
        }
        pickAndCommit(index)
    }

    private fun pickAndCommit(index: Int) {
        val e = engine ?: return
        val detail = e.commitDetail(index) ?: return
        commitText(detail.first)
        // 用户词回写（§4.5：提交 → SQLite；引擎内已 Learn 提升会话内权重）
        // v1.7.3 审计修复：只回写 ≥2 字词——单字每次都回写会把用户词库
        // 灌满"的/了/我"这类高频单字（词频虚高、候选被污染），与主流 IME 一致
        if (detail.first.length >= 2 && !detail.first[0].isDigit()) {
            Thread { db?.upsert(detail.first, detail.second) }.start()
        }
        refreshKeyboard()
        refreshCandidates()
    }

    /** 合成区点击：用户所打拼音/英文原文直接上屏（§4.2.1 左侧合成区交互） */
    private fun commitComposing() {
        val e = engine ?: return
        val raw = e.input()
        if (raw.isNotEmpty()) commitText(raw)
        e.reset()
        hwrCandidates = emptyList()
        refreshCandidates()
    }

    /**
     * 候选刷新 v1.2：
     * - 合成区显示自动分词串（每击键由核心重算：音节边界空格呈现、
     *   手动锚点紧贴前段；用户痛点"连打多字只见单字"时可见当前切分）；
     * - 拼音合成中显示固定"分词"按钮（手动分词锚定）；
     * - 引擎候选横滑展示（长候选不再截断）；
     * - T9 显示可能拼音串横滑条；手写模式显示手写候选通道；
     * - 英文模式候选首位插入"原文上屏"选项。
     */
    private fun refreshCandidates() {
        // 修复：框架的 onStartInput 可能早于 onCreateInputView（视图惰性创建），
        // 此时 candidateBar 尚未初始化，直接访问会抛 UninitializedPropertyAccessException
        // 导致 IME 进程崩溃循环（表现为键盘永不弹出、应用反复崩溃）。
        if (!::candidateBar.isInitialized) return
        val e = engine
        if (e == null) {
            candidateBar.setComposing("")
            candidateBar.setSplitVisible(false)
            candidateBar.setCandidates(emptyList())
            return
        }
        val raw = e.input()
        // v1.3 上下文通道：光标前已上屏文本传引擎 → 候选结合上文重排
        //（打完"的"再按 d，首选让位给"地/大"；回答用户"输入法能读输入框文字吗"：
        //  可以，IME 通过 InputConnection 读取光标前文本，仅取尾部 18 字节参与重排）
        if (layoutMode != GkLayout.LATIN) {
            try {
                val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString()
                e.setContext(before)
            } catch (_: Throwable) {
                e.setContext(null)
            }
        }
        // —— 合成区：拼音/T9 模式显示自动分词串，英文模式显示原文 ——
        val display = if (layoutMode == GkLayout.LATIN) raw else e.segmentedInput()
        candidateBar.setComposing(display)
        // —— 分词按钮：拼音模式且有合成内容时可见 ——
        candidateBar.setSplitVisible(layoutMode != GkLayout.LATIN && raw.isNotEmpty())

        if (panelMode == Panel.HWR && hwrCandidates.isNotEmpty()) {
            candidateBar.setCandidates(hwrCandidates)
            return
        }

        // v1.5.8 下文预测：无合成输入时（所有键盘模式），按上文 token 预测下一词
        //（内置高频搭配 + 用户历史 bigram + 联系人实体电话/邮箱）
        if (raw.isEmpty() && panelMode == Panel.KEYBOARD &&
            com.glasskey.app.GkPrefs.nextPredict(this)
        ) {
            val preds = com.glasskey.ime.predict.NextWordPredictor
                .predict(this, lastCommitted, 8)
            if (preds.isNotEmpty()) {
                predictCands = preds
                candidateBar.setSplitVisible(false)
                candidateBar.setCandidates(preds)
                return
            }
        }
        predictCands = emptyList()

        val n = e.candidateCount()
        // v1.7.2：候选来源标记——兜底通道（九键索引/连续拼音解析/预测）展示的
        // 候选不在引擎里，点击必须按展示列表提交，否则除第 1 个外全部
        // "点了没反应"（悬浮模式打多字词时必现，pickAndCommit 走引擎下标取空）
        candsFromEngine = n > 0
        val engineCands: List<String> = when {
            n > 0 -> (0 until n).map { i -> e.candidate(i) }
            // v1.5.5：九键优化——引擎词候选之外，把 T9 可选拼音串送连续拼音/词库
            // 解析器取词候选融合（与 26 键同通道：词库组句/用户词/学习记忆共用），
            // 无词命中时回落原"拼音串横滑条"（§4.2.2）
            layoutMode == GkLayout.PINYIN_9 && raw.isNotEmpty() -> {
                val pyList = (0 until e.t9PinyinCount()).map { i -> e.t9Pinyin(i) }
                // v1.7 九键优化（用户反馈：九键"打半天只能打出一个字"）——
                // 原 bug：引擎 t9Pinyin 会解出大量"单音节拼音串"（按 64 → "ni"/"nh"/…），
                // 送 resolveContinuation 必然命中单字 → words 恒非空 → 首字母简拼
                // 通道（T9InitialIndex）永远轮不到 → 多字词出不来。
                // 现三层融合，多字词优先：
                // ① 简拼通道（T9InitialIndex，词频排序）——首字母形态的主力；
                // ② 全拼音节串 → 连续拼音解析的多字词（过滤掉单字）；
                // ③ 引擎自身词候选（candidateCount>0 时已在上面分支）。
                val digits = raw.filter { it in '2'..'9' }
                val abbrev = if (digits.length >= 2)
                    com.glasskey.ime.t9.T9InitialIndex.candidates(
                        digits, GkPrefs.candidateLimit(this)) else emptyList()
                val fullWords = pyList.take(6).flatMap { py ->
                    (resolveContinuation(py)?.candidates ?: emptyList())
                        .filter { it.length >= 2 }   // 九键下单字候选意义不大
                }.distinct()
                // 简拼 exact 命中与全拼多字词交替融合（简拼在前，词频已排序）
                (abbrev + fullWords).distinct().take(GkPrefs.candidateLimit(this))
                    .ifEmpty { pyList }
            }
            // v1.4 连续拼音解析：引擎无候选（首字母/前缀/混合形态）→ 第二层词库候选
            layoutMode == GkLayout.PINYIN_26 && raw.length >= 2 ->
                resolveContinuation(raw)?.candidates ?: emptyList()
            else -> emptyList()
        }
        // 英文模式：首位插入原文上屏选项（用户看不到自己打的词 → 修正通道之外可直上原文）
        val shown = if (layoutMode == GkLayout.LATIN && raw.isNotEmpty()) {
            listOf(raw) + engineCands.filter { it != raw }
        } else engineCands
        // v1.5.9：开发者日志——候选生成结果（长句/混拼排查核心观测点）
        if (com.glasskey.ime.dev.GkLogger.enabled && shown.isNotEmpty()) {
            com.glasskey.ime.dev.GkLogger.log("cand",
                "mode=$layoutMode raw='$raw' top=${shown.take(5)}")
        }
        // v1.7.2：记录实际展示的候选列表（点击按此提交，通道一致）
        shownCands = shown
        candidateBar.setCandidates(shown)
    }

    private fun refreshKeyboard() {
        if (!::keyboardView.isInitialized) return
        val base = when (layoutMode) {
            GkLayout.PINYIN_9 -> KeyboardDefs.t9()
            GkLayout.LATIN -> KeyboardDefs.latin(
                upper = capMode == LatinCapHelper.Mode.LOCK
            )
            else -> KeyboardDefs.pinyin26()
        }
        // v1.7.1：数字小键盘（独立布局，键盘「123」键切换进出，NumPad 风格）
        if (numpadMode) {
            keyboardView.keyboard = numpad()
            keyboardView.accentCode = KeyboardDefs.CODE_NUMPAD
            keyboardView.invalidate()
            return
        }
        // 中/英状态可见性（真机反馈：切换不明显）：键帽直接显示当前语言并高亮
        val langLabel = if (layoutMode == GkLayout.LATIN) "英" else "中"
        keyboardView.keyboard = base.withKeyLabel(KeyboardDefs.CODE_LAYOUT_SWITCH, langLabel)
        keyboardView.accentCode = KeyboardDefs.CODE_LAYOUT_SWITCH
        // v1.6.0：设置里切换上滑标点开关后立即生效（此前只在 onCreateInputView 读一次）
        keyboardView.swipePunctEnabled = GkPrefs.swipePunct(this)
        keyboardView.invalidate()
    }

    /** v1.6.0：打字内存观测计数（每 100 次上屏采样一次） */
    private var memTick = 0
    /** v1.6.3：退格长按日志降噪计数 */
    private var bsLogTick = 0

    private fun commitText(text: String) = commitText(text, fromVoice = false)

    /**
     * v1.5.3 语音学习记忆：语音上屏后 30s 内，用户用退格删掉尾部片段并
     * 以拼音候选等方式提交替换词 → 记录「ASR 原词 → 用户修正词」映射（热度 +1），
     * 下次语音识别输出同形词时由后处理热词通道自动替换。
     */
    private fun commitText(text: String, fromVoice: Boolean) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        // v1.7.4：敏感字段（密码/电话）只上屏，不学习、不记上下文
        if (sensitiveField) return
        // v1.6.0：打字持续内存观测（每 100 次上屏一条，避免刷屏）
        if (com.glasskey.ime.dev.GkLogger.enabled) {
            if (++memTick % 100 == 0) {
                com.glasskey.ime.dev.GkMemMonitor.logSnapshot("after$memTick commits", this)
            }
        }
        if (com.glasskey.ime.dev.GkLogger.enabled) {
            com.glasskey.ime.dev.GkLogger.log("commit",
                "${if (fromVoice) "voice" else "key"} '${text.take(24)}' prev='$lastCommitted'")
        }
        // v1.7.4：学习总闸 = 设置开关 且 非敏感字段（密码/电话绝不学习）
        val learnOn = !sensitiveField && GkPrefs.learning(this)
        val now = System.currentTimeMillis()
        if (fromVoice) {
            voiceCommitAt = now
            voiceCommitText = text
            delSinceVoice = 0
            voiceDeletedText.setLength(0)  // 新一句语音：清空修正捕捉缓冲
            // v1.5.8：语音上屏同样进入下文预测上下文（长句不学习 bigram，仅记 token）
            if (learnOn && text.isNotBlank() && text.length <= 20) {
                com.glasskey.ime.predict.NextWordPredictor.observe(this, lastCommitted, text)
            }
            lastCommitted = text
            return
        }
        // v1.5.8 下文预测学习：记录 (上一词, 本次上屏) bigram + 实体抽取（电话/邮箱/姓名）
        if (text.isNotBlank()) {
            if (learnOn) com.glasskey.ime.predict.NextWordPredictor.observe(this, lastCommitted, text)
            lastCommitted = text
        }
        // v1.6.3 短期热词：对最终上屏结果分词保存（3 天热表，下次输入结合）
        if (learnOn && text.isNotBlank() && text.length <= 40) {
            com.glasskey.ime.predict.RecentHotWords.addFromSentence(this, text, contLexWordSet)
        }
        // 修正捕捉窗口
        // v1.6.3：from 优先用真实抓取的被删文本 voiceDeletedText ——
        // 覆盖句中改词（删除片段不在语音串尾部）与连续多处修改；
        // 抓取失败（ic 不可用）时回退旧的"尾部 N 字"推断。
        if (voiceCommitAt > 0 && now - voiceCommitAt < VOICE_LEARN_WINDOW_MS &&
            delSinceVoice > 0 && text.isNotBlank() && learnOn
        ) {
            val realDeleted = voiceDeletedText.toString()
            val from = if (realDeleted.isNotBlank()) realDeleted
            else voiceCommitText.substring(
                voiceCommitText.length - delSinceVoice.coerceAtMost(voiceCommitText.length))
            val f = from.trim()
            // v1.7.3 学习防护（审计修复：语音"全删重打"被学成"整句→单字"，
            // 之后每次语音整句都被替换成那一个字）：
            //  ① from ≤ 8 字——纠错是词级的，整句/长片段是"重写"不是"修正"；
            //  ② 与替换词长度差 ≤ 2——长度悬殊（整段换一字）不学；
            //  ③ 全删语音串（删除内容≈整句原文）→ 视为重写，不学；
            //  ④ from 至少含一个文字（纯标点/空白删了再打字不算修正）。
            val wholeRewrite = realDeleted.isNotBlank() &&
                voiceCommitText.trim().isNotEmpty() &&
                voiceCommitText.trim().all { ch -> realDeleted.contains(ch) }
            val learnable = f.isNotEmpty() && f != text &&
                f.length <= 8 &&
                kotlin.math.abs(f.length - text.length) <= 2 &&
                !wholeRewrite &&
                f.any { it.isLetter() }
            if (learnable) {
                com.glasskey.app.GkPrefs.addVoiceLearn(this, f, text)
                android.util.Log.i("GkVoiceLearn", "learned: '$f' -> '$text'")
                com.glasskey.ime.dev.GkLogger.log("voiceLearn", "'$f' -> '$text'")
            } else {
                com.glasskey.ime.dev.GkLogger.log("voiceLearn",
                    "skip learn from='$f'(len=${f.length}) to='$text' whole=$wholeRewrite")
            }
            // 不关窗口：一句话里可能连改多处，继续捕捉下一处修正
            delSinceVoice = 0
            voiceDeletedText.setLength(0)
        }
    }

    /** 语音上屏时间戳 / 原文 / 其后退格计数（学习记忆状态） */
    private var voiceCommitAt = 0L
    private var voiceCommitText = ""
    private var delSinceVoice = 0
    /** v1.6.3：修正窗口内真实抓取的被删文本（句中改词学习用） */
    private val voiceDeletedText = StringBuilder()

    /** 语音修正窗口内的退格计数（v1.5.8 收口：主键盘/手写面板共用） */
    private fun noteVoiceWindowBackspace() {
        if (voiceCommitAt > 0 &&
            System.currentTimeMillis() - voiceCommitAt < VOICE_LEARN_WINDOW_MS
        ) {
            // v1.7.3 审计修复：删除累计超过语音串长度说明语音文本已删光、
            // 现在删的是更早的历史输入——不能把它抓进 from（污染学习对）
            if (delSinceVoice >= voiceCommitText.length) return
            delSinceVoice++
            // v1.6.3：抓取真实被删文本（此前只数次数、按"语音串尾部 N 字"推断，
            // 句中改词时删除片段不在尾部 → 学习对取错/学不进去）。
            // 逐次退格删的是光标前 1 字，按删除顺序拼接即原文片段顺序。
            if (voiceDeletedText.length < 30) {
                try {
                    val c = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
                    if (!c.isNullOrEmpty() && c.first() != '\n') voiceDeletedText.append(c)
                } catch (_: Throwable) { }
            }
        }
    }

    companion object {
        const val BUBBLE_TIMEOUT_MS = 10_000L

        /** v1.4 连续拼音直接上屏的停顿防抖（ms） */
        const val CONT_DEBOUNCE_MS = 350L

        /** 语音修正捕捉窗口（ms） */
        const val VOICE_LEARN_WINDOW_MS = 30_000L
    }
}
