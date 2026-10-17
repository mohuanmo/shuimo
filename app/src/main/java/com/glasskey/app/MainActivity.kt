package com.glasskey.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.glasskey.R
import com.glasskey.ime.engine.GkFuzzy
import com.glasskey.ime.ui.Theme
import java.io.File

/**
 * 设置界面 v1.5 —— Material 3 Expressive 风格重构：
 * 大标题 + 分区圆角卡片（24dp 圆角、tonal 底色）+ 大号 Switch/SeekBar +
 * 主题色圆点选择（水墨五色），去除旧版平铺 CheckBox 排版。
 * 纯框架 API 代码构建 UI，无 androidx（§D-05）。
 *
 * 分区：
 *  ① 启用引导（状态卡） ② 输入（学习/模糊音/候选数/连续拼音/用户词）
 *  ③ 语音与手写（校平/模型状态） ④ 外观（主题色/键盘高度/候选字号）
 *  ⑤ 功能（剪贴板/悬浮路线 B） ⑥ 关于（词库规模/隐私）
 */
class MainActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private var step1: TextView? = null
    private var step2: TextView? = null
    private var btnSwitch: Button? = null
    private var aboutCardTapCount = 0
    private var devCardAdded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        try {
            buildUi()
        } catch (e: Throwable) {
            // 兜底界面：任何 UI 构建异常都不至于白屏/闪退，直接把错误显示出来
            CrashGuard.install(this)
            val tv = TextView(this).apply {
                text = "设置界面加载失败，请截图反馈：\n\n${android.util.Log.getStackTraceString(e)}"
                textSize = 13f; setPadding(40, 60, 40, 40)
            }
            setContentView(tv)
        }
        // 上次崩溃日志入口
        CrashGuard.take(this)?.let { log -> showCrashDialog(log) }
    }

    private fun showCrashDialog(log: String) {
        try {
            val btn = pillButton("检测到上次异常退出 · 点此分享崩溃日志", 0xFFD24D57.toInt(), 0xFFFFFFFF.toInt())
            btn.setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, log)
                    putExtra(Intent.EXTRA_TITLE, "水墨输入法崩溃日志")
                }
                startActivity(Intent.createChooser(send, "分享崩溃日志"))
            }
            val root = (window.decorView as? android.view.ViewGroup)?.findViewById<android.view.ViewGroup>(android.R.id.content)
            root?.addView(btn, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM).apply { setMargins(24, 0, 24, 32) })
        } catch (_: Throwable) { }
    }

    private fun buildUi() {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF10161D.toInt() else 0xFFF5F7FB.toInt()
        val cardBg = if (dark) 0xFF1A222C.toInt() else 0xFFFFFFFF.toInt()
        val titleC = if (dark) 0xFFEDF2F8.toInt() else 0xFF16202B.toInt()
        val subC = if (dark) 0xFF9AA8B6.toInt() else 0xFF7A8694.toInt()
        val accent = Theme.accentLight(GkPrefs.accentIndex(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 18).toInt(), (d * 20).toInt(), (d * 18).toInt(), (d * 28).toInt())
        }
        // v1.5.3 大屏适配：内容限宽 640dp 居中，平板/大屏不再通栏拉伸、开关与文案错位
        val centerWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        centerWrap.addView(root, LinearLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels, (d * 640).toInt()),
            LinearLayout.LayoutParams.MATCH_PARENT))
        val scroll = android.widget.ScrollView(this).apply { addView(centerWrap) }
        setContentView(scroll)
        scroll.background = GradientDrawable().apply { setColor(bg) }

        // —— 大标题（M3E display）——
        root.addView(text("水墨", 40f, titleC, bold = true))
        root.addView(text("磨砂质感 · 拼音 / T9 / 拉丁 · 全离线", 14f, subC).apply {
            setPadding(0, (d * 2).toInt(), 0, (d * 16).toInt())
        })

        // —— ① 启用引导卡 ——
        val guide = card(cardBg)
        step1 = text("", 15f, accent)
        step2 = text("", 15f, accent)
        val btnEnable = pillButton("前往系统设置启用", accent, cardBg)
        btnEnable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btnSwitch = pillButton("切换到水墨输入", accent, cardBg)
        btnSwitch!!.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
        guide.addView(step1, matchWrap())
        guide.addView(step2, matchWrap())
        guide.addView(btnEnable, matchWrap().apply { topMargin = (d * 10).toInt() })
        guide.addView(btnSwitch, matchWrap().apply { topMargin = (d * 8).toInt() })
        root.addView(section(root, guide, "开始使用"))

        // —— ② 输入 ——
        val input = card(cardBg)
        input.addView(switchRow("用户词学习", "自动记录常用词并提升排序（仅存本机）",
            GkPrefs.learning(this), titleC, subC) { on ->
            GkPrefs.setLearning(this, on)
            toast("已${if (on) "开启" else "关闭"}学习（重启输入法生效）")
        })
        input.addView(subhead("模糊音", titleC))
        val fuzzySpecs = listOf(
            GkFuzzy.ZH_Z to "z / zh", GkFuzzy.CH_C to "c / ch", GkFuzzy.SH_S to "s / sh",
            GkFuzzy.AN_ANG to "an / ang", GkFuzzy.EN_ENG to "en / eng", GkFuzzy.IN_ING to "in / ing",
            GkFuzzy.N_L to "n / l", GkFuzzy.F_H to "f / h",
        )
        var mask = GkPrefs.fuzzyMask(this)
        // v1.5.3 大屏适配修复：原 GridLayout 每行显式 columnSpec(0,1,1f) 把全部行
        // 钉在第 0 列，大屏上文字被挤压出屏只剩开关。改为左右两列各占 50% 的
        // 等宽 LinearLayout，任何分辨率都稳定换行。
        val fuzzyCols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val halves = fuzzySpecs.withIndex().groupBy { it.index % 2 }
        for ((_, items) in halves) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for ((_, specPair) in items) {
                val (bit, label) = specPair
                col.addView(switchRow(label, null, mask and bit != 0, titleC, subC) { on ->
                    mask = if (on) mask or bit else mask and bit.inv()
                    GkPrefs.setFuzzyMask(this, mask)
                    toast("重启输入法后生效")
                }, matchWrap())
            }
            fuzzyCols.addView(col, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        input.addView(fuzzyCols, matchWrap().apply { topMargin = (d * 4).toInt() })
        input.addView(subhead("候选", titleC))
        input.addView(sliderRow("候选数量", 5, 50, GkPrefs.candidateLimit(this), "%d 个",
            titleC, subC, accent) { v -> GkPrefs.setCandidateLimit(this, v) })
        input.addView(subhead("连续拼音", titleC))
        input.addView(switchRow("高置信自动上屏", "停顿 350ms 后首选直接上屏",
            GkPrefs.continuationAutoCommit(this), titleC, subC) { on ->
            GkPrefs.setContinuationAutoCommit(this, on)
        })
        input.addView(sliderRow("上屏置信度阈值", 50, 99,
            (GkPrefs.continuationHighConfidence(this) * 100).toInt(), "%d%%",
            titleC, subC, accent) { v -> GkPrefs.setContinuationHighConfidence(this, v / 100.0) })
        input.addView(switchRow("无条件直接上屏", "关闭低置信保护（每次跳过记录日志）",
            GkPrefs.continuationAlwaysCommit(this), titleC, subC) { on ->
            GkPrefs.setContinuationAlwaysCommit(this, on)
        })
        input.addView(switchRow("下文预测", "依据上句预测下一个词/电话/邮箱/表情（v1.5.8）",
            GkPrefs.nextPredict(this), titleC, subC) { on ->
            GkPrefs.setNextPredict(this, on)
        })
        input.addView(pillButton("用户词管理", accent, cardBg).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, UserDictActivity::class.java)) }
        }, matchWrap().apply { topMargin = (d * 10).toInt() })
        input.addView(pillButton("学习数据（联系人卡片 / 搭配 / 语音纠错）", accent, cardBg).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, LearnDataActivity::class.java)) }
        }, matchWrap().apply { topMargin = (d * 8).toInt() })
        root.addView(section(root, input, "输入"))

        // —— ③ 语音与手写 ——
        val voice = card(cardBg)
        voice.addView(switchRow("声音校平", "录音自动增益 / 降噪 / 响度均衡",
            GkPrefs.voiceLevelerEnabled(this), titleC, subC) { on ->
            GkPrefs.setVoiceLevelerEnabled(this, on)
        })
        voice.addView(switchRow("静音自动停", "开启后说话停顿约 0.8 秒自动结束；关闭则持续识别，手动点停止上屏",
            GkPrefs.voiceAutoStop(this), titleC, subC) { on ->
            GkPrefs.setVoiceAutoStop(this, on)
        })
        voice.addView(text(
            "语音识别：中文流式模型已内置（离线） · " +
                "手写：${com.glasskey.ime.hwr.HwrModelContract.ready(File(filesDir, "models")).let { if (it) "模型已加载（识别效果仍不可用）" else "模型无法加载，功能不可用" }}",
            13f, subC).apply {
            setPadding(0, (d * 6).toInt(), 0, 0)
        })
        // v1.6.3：语音引擎常驻时长（多久没用语音就卸载模型；用时可自定义分钟数）
        voice.addView(pillButton(
            "语音引擎常驻：${voiceKeepLabel(GkPrefs.voiceKeepMinutes(this))}", accent, cardBg).apply {
            setOnClickListener { showVoiceKeepDialog() }
        }, matchWrap().apply { topMargin = (d * 8).toInt() })
        root.addView(section(root, voice, "语音与手写"))

        // —— ④ 外观（v1.5 新增）——
        val look = card(cardBg)
        look.addView(subhead("主题色", titleC))
        look.addView(accentSwatches(cardBg), matchWrap())
        look.addView(sliderRow("键盘高度", 80, 130, GkPrefs.keyHeightScale(this), "%d%%",
            titleC, subC, accent) { v -> GkPrefs.setKeyHeightScale(this, v) })
        look.addView(sliderRow("候选字号", 14, 22, GkPrefs.candidateTextSize(this), "%d sp",
            titleC, subC, accent) { v -> GkPrefs.setCandidateTextSize(this, v) })
        look.addView(switchRow("字母键上滑标点", "26 键字母向上滑出 ！？、《》等标点（v1.5.8）",
            GkPrefs.swipePunct(this), titleC, subC) { on ->
            GkPrefs.setSwipePunct(this, on)
            toast("重启输入法键盘后生效")
        })
        root.addView(section(root, look, "外观"))

        // —— ⑤ 功能 ——
        val feat = card(cardBg)
        feat.addView(switchRow("剪贴板历史", "键盘工具栏 📋 打开（敏感内容不入库）",
            GkPrefs.clipboardEnabled(this), titleC, subC) { on ->
            GkPrefs.setClipboardEnabled(this, on)
        })
        feat.addView(switchRow("悬浮键盘（真悬浮窗）", "需「显示在其他应用上层」权限，未授权自动降级",
            GkPrefs.floatingRouteB(this), titleC, subC) { on ->
            GkPrefs.setFloatingRouteB(this, on)
        })
        feat.addView(sliderRow("悬浮窗宽度", 20, 95, GkPrefs.floatingWidthPct(this), "%d%%",
            titleC, subC, accent) { v -> GkPrefs.setFloatingWidthPct(this, v) })
        root.addView(section(root, feat, "功能"))

        // —— ⑥ 学习数据（v1.5.8 收纳到独立 M3E 页面：联系人卡片/搭配历史/语音纠错）——
        val learn = card(cardBg)
        val learnContacts = com.glasskey.ime.predict.NextWordStats.contacts(this)
        val learnBigrams = com.glasskey.ime.predict.NextWordStats.bigrams(this)
        val learnVoice = GkPrefs.voiceLearnEntries(this)
        learn.addView(text(
            "联系人 ${learnContacts.size} · 搭配 ${learnBigrams.size} · 语音纠错 ${learnVoice.size}（仅存本机）",
            14f, titleC))
        learn.addView(pillButton("查看学习数据", accent, cardBg).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, LearnDataActivity::class.java)) }
        }, matchWrap().apply { topMargin = (d * 10).toInt() })
        // v1.7.4：一键清除全部学习数据（对齐主流大厂"清除自学习记录"能力）
        learn.addView(pillButton("一键清除全部学习数据", accent, cardBg).apply {
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("清除全部学习数据")
                    .setMessage("将清空：搭配记录、联系人卡片、语音纠错记忆、短期热词。词库不受影响。此操作不可恢复。")
                    .setPositiveButton("清除") { _, _ ->
                        com.glasskey.ime.predict.NextWordStats.clearBigrams(this@MainActivity)
                        com.glasskey.ime.predict.NextWordStats.clearContacts(this@MainActivity)
                        GkPrefs.clearVoiceLearn(this@MainActivity)
                        com.glasskey.ime.predict.RecentHotWords.clear(this@MainActivity)
                        toast("学习数据已全部清除")
                        recreate()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }, matchWrap().apply { topMargin = (d * 10).toInt() })
        root.addView(section(root, learn, "学习数据"))

        // —— ⑥b 词库管理（v1.6.0：条数统计 + 预览/完整查看 + 订阅导入 本地/网络）——
        val lexMgr = card(cardBg)
        lexMgr.addView(text(
            "组句词库 ${LexiconStore.lexiconCount(this)} 条 · 语料词对 ${LexiconStore.bigramCount(this)} 条",
            14f, titleC))
        lexMgr.addView(pillButton("管理词库 / 词对（预览 · 订阅导入）", accent, cardBg).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LexiconManagerActivity::class.java))
            }
        }, matchWrap().apply { topMargin = (d * 10).toInt() })
        root.addView(section(root, lexMgr, "词库管理"))

        // —— ⑦ 关于 ——
        val about = card(cardBg)
        // v1.5.3 文案修正：词库随 APK 离线内置，不再出现"待部署"这类设备端误导文案
        about.addView(text("词库：中文 35 万+ · 英文 12 万+ 词条（离线内置，开箱即用）", 14f, titleC))
        about.addView(text("版本 1.5.0（M6） · 词库 v1.5 大词库版", 13f, subC).apply {
            setPadding(0, (d * 4).toInt(), 0, 0)
        })
        about.addView(text("隐私承诺：所有输入与学习数据仅保存在本机，可随时清空；仅订阅下载使用网络，不上传任何数据。",
            13f, subC).apply { setPadding(0, (d * 6).toInt(), 0, 0) })
        root.addView(section(root, about, "关于").apply { tag = "about_section" })

        // —— ⑧ 开发者入口（隐藏：关于卡连点 3 次解锁，解锁状态持久化）——
        if (GkPrefs.devUnlocked(this)) addDevCard(root, cardBg, accent)
        aboutCardTapCount = 0
        about.setOnClickListener {
            aboutCardTapCount++
            if (aboutCardTapCount >= 3 && !GkPrefs.devUnlocked(this)) {
                GkPrefs.setDevUnlocked(this, true)
                toast("已进入开发者模式")
                addDevCard(root, cardBg, accent)
            }
        }

        // 麦克风授权引导（§5.6）
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            Toast.makeText(this, "请允许麦克风权限以使用语音输入（仅本地处理）", Toast.LENGTH_LONG).show()
        }
    }

    // ── M3E 组件 ────────────────────────────────────────────

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** 分区：小标题 + 24dp 圆角卡片（M3E card） */
    private fun section(root: LinearLayout, cardView: LinearLayout, title: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(title, 17f,
            if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFFEDF2F8.toInt() else 0xFF16202B.toInt(),
            bold = true).apply { setPadding((d * 4).toInt(), (d * 18).toInt(), 0, (d * 8).toInt()) })
        box.addView(cardView, matchWrap())
        // 注意：不在此处 addView 到 root —— 调用方已统一 root.addView(section(...))，
        // 此处再加会导致 "child already has a parent" 闪退（v1.5.0 实际事故）
        return box
    }

    private fun card(bg: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt(), (d * 16).toInt())
        background = GradientDrawable().apply {
            cornerRadius = d * 24f   // M3E expressive shape
            setColor(bg)
        }
    }

    private fun subhead(s: String, color: Int): TextView = text(s, 14f, color, bold = true)

    /** v1.6.3：语音引擎常驻时长标签 */
    private fun voiceKeepLabel(min: Int): String = when {
        min == 0 -> "每次用完即释放"
        min < 0 -> "常驻（不卸载）"
        else -> "闲置 ${min} 分钟后卸载"
    }

    /** v1.6.3：语音引擎常驻时长选择（预设 + 自定义分钟数） */
    private fun showVoiceKeepDialog() {
        val options = arrayOf("每次用完即释放", "闲置 5 分钟后卸载", "闲置 15 分钟后卸载",
            "闲置 30 分钟后卸载（默认）", "常驻（不卸载）", "自定义分钟数…")
        val cur = GkPrefs.voiceKeepMinutes(this)
        val checked = when {
            cur == 0 -> 0; cur == 5 -> 1; cur == 15 -> 2; cur == 30 -> 3; cur < 0 -> 4; else -> 5
        }
        AlertDialog.Builder(this)
            .setTitle("语音引擎常驻时长")
            .setSingleChoiceItems(options, checked) { _, which ->
                val v = intArrayOf(0, 5, 15, 30, -1)[which.coerceAtMost(4)]
                if (which == 5) {
                    showVoiceKeepCustom()
                } else {
                    GkPrefs.setVoiceKeepMinutes(this, v)
                    toast(voiceKeepLabel(v))
                }
                // 刷新按钮文案
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showVoiceKeepCustom() {
        val input = EditText(this).apply {
            hint = "1 - 1440 分钟（例如 45）"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 20).toInt(), (d * 8).toInt(), (d * 20).toInt(), 0)
        }
        wrap.addView(input, matchWrap())
        AlertDialog.Builder(this)
            .setTitle("自定义常驻时长")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < 1 || v > 1440) {
                    toast("请输入 1-1440 之间的分钟数")
                } else {
                    GkPrefs.setVoiceKeepMinutes(this, v)
                    toast(voiceKeepLabel(v))
                    recreate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun switchRow(
        title: String, summary: String?, checked: Boolean,
        titleC: Int, subC: Int, onChange: (Boolean) -> Unit,
    ): View {
        // v1.5.4 修复"设置项文本全部不可见"：row 为 VERTICAL 布局，而文本列用了
        // width=0 + weight（weight 在竖向布局分配的是高度）→ 文本宽度为 0 消失。
        // 现改为内部横向行：文本列 weight 1 占满、开关靠右。
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (d * 6).toInt(), 0, (d * 6).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, titleC))
        if (summary != null) texts.addView(text(summary, 12f, subC))
        val sw = Switch(this).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList.valueOf(
                if (checked) Theme.accentLight(GkPrefs.accentIndex(this@MainActivity)) else 0xFFB9C2CC.toInt())
            trackTintList = android.content.res.ColorStateList.valueOf(
                if (checked) (Theme.accentLight(GkPrefs.accentIndex(this@MainActivity)) and 0x55FFFFFF or 0x55000000.toInt())
                else 0x33808A96)
            setOnCheckedChangeListener { _, on ->
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    if (on) Theme.accentLight(GkPrefs.accentIndex(this@MainActivity)) else 0xFFB9C2CC.toInt())
                onChange(on)
            }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // 整行点击切换（M3E 大触区）
        row.isClickable = true
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun sliderRow(
        title: String, min: Int, max: Int, value: Int, fmt: String,
        titleC: Int, subC: Int, accent: Int, onChange: (Int) -> Unit,
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = text(String.format(fmt, value), 15f, titleC, bold = true)
        row.addView(label.apply { setPadding(0, (d * 10).toInt(), 0, 0) })
        row.addView(text("$title（$min – $max）", 12f, subC))
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = String.format(fmt, p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onChange((s!!.progress) + min)
                    toast("重启输入法后完全生效")
                }
            })
        }
        row.addView(sb, matchWrap().apply { topMargin = (d * 4).toInt() })
        return row
    }

    /** 主题色圆点选择（水墨五色，M3E expressive color） */
    private fun accentSwatches(cardBg: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val selected = GkPrefs.accentIndex(this)
        Theme.ACCENTS.forEachIndexed { i, (name, rgb) ->
            val dot = TextView(this).apply {
                text = name; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF000000.toInt() or rgb)
                    setStroke((d * (if (i == selected) 3 else 0)).toInt(), 0xFF2E7CF6.toInt())
                }
            }
            val lp = LinearLayout.LayoutParams((d * 44).toInt(), (d * 44).toInt())
            lp.marginEnd = (d * 10).toInt()
            dot.setOnClickListener {
                GkPrefs.setAccentIndex(this, i)
                toast("主题色：$name（重启输入法后键盘生效）")
                recreate()
            }
            row.addView(dot, lp)
        }
        return row
    }

    private fun pillButton(label: String, accent: Int, cardBg: Int): Button =
        Button(this).apply {
            text = label; textSize = 15f; isAllCaps = false
            setTextColor(accent)
            background = GradientDrawable().apply {
                cornerRadius = d * 999f
                setColor(accent and 0x1AFFFFFF or 0x14000000.toInt())
                setStroke((d * 1.4f).toInt(), accent)
            }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** v1.5.8：开发者模式卡片（关于卡连点 3 次解锁） */
    private fun addDevCard(root: LinearLayout, cardBg: Int, accent: Int) {
        if (devCardAdded) return
        devCardAdded = true
        val dev = card(cardBg)
        dev.addView(text("开发者模式已启用", 15f, accent, bold = true))
        dev.addView(text("运行日志记录 / 查看与复制（仅内存，不上传）", 12f,
            if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFF9AA8B6.toInt() else 0xFF7A8694.toInt()
        ).apply { setPadding(0, (d * 4).toInt(), 0, (d * 10).toInt()) })
        dev.addView(pillButton("进入开发者选项", accent, cardBg).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, DevActivity::class.java)) }
        }, matchWrap())
        // 插到"关于"分区之后（root 末尾追加即可）
        root.addView(dev, matchWrap().apply { topMargin = (d * 12).toInt() })
    }

    // ── 词库信息 / 状态 ─────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "麦克风权限已授予，语音输入可用", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val chosen = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )?.contains(packageName) == true
        val accent = Theme.accentLight(GkPrefs.accentIndex(this))
        step1?.text = getString(
            if (enabled) R.string.step1_status_on else R.string.step1_status_off
        )
        step1?.setTextColor(if (enabled) 0xFF3DA268.toInt() else accent)
        step2?.text = getString(
            if (chosen) R.string.step2_status_on else R.string.step2_status_off
        )
        step2?.setTextColor(if (chosen) 0xFF3DA268.toInt() else accent)
        btnSwitch?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    companion object {
        /** 语音面板 → 主 App 触发麦克风运行时授权（§5.6） */
        const val EXTRA_REQUEST_MIC = "extra_request_mic"
    }
}

private fun matchWrap() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
)
