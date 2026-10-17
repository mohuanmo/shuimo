package com.glasskey.app

import android.content.Context
import com.glasskey.ime.engine.GkFuzzy

/** 全局偏好（§5.2 设置项；SharedPreferences 纯框架实现） */
object GkPrefs {
    private const val FILE = "glasskey_prefs"
    private const val KEY_FUZZY = "fuzzy_mask"
    private const val KEY_LEARNING = "learning_enabled"
    private const val KEY_CLIPBOARD = "clipboard_enabled"
    private const val KEY_FLOAT_B = "floating_route_b"

    // ── v1.5 外观自定义（M3E 设置界面）──
    private const val KEY_ACCENT = "accent_index"
    private const val KEY_KEY_HEIGHT = "key_height_scale"
    private const val KEY_CAND_SIZE = "candidate_text_size"

    /** 主题色索引（0 墨蓝 1 黛青 2 绛紫 3 玄墨 4 朱砂） */
    fun accentIndex(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_ACCENT, 0)

    fun setAccentIndex(ctx: Context, idx: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACCENT, idx).apply()
    }

    /** 键盘高度缩放（百分比 80..130，默认 100） */
    fun keyHeightScale(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_KEY_HEIGHT, 100)

    fun setKeyHeightScale(ctx: Context, pct: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_KEY_HEIGHT, pct.coerceIn(80, 130)).apply()
    }

    /** 候选字号（sp 14..22，默认 18） */
    fun candidateTextSize(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_CAND_SIZE, 18)

    fun setCandidateTextSize(ctx: Context, sp: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CAND_SIZE, sp.coerceIn(14, 22)).apply()
    }

    // ── v1.4 候选数量统一配置（普通词/单字/手写候选共用，默认 30） ──
    private const val KEY_CAND_LIMIT = "candidate_limit"

    fun candidateLimit(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_CAND_LIMIT, 30).coerceIn(5, 50)

    fun setCandidateLimit(ctx: Context, n: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CAND_LIMIT, n.coerceIn(5, 50)).apply()
    }

    // ── v1.4 语音校平开关（默认开） ──
    private const val KEY_VOICE_LEVELER = "voice_leveler_enabled"

    fun voiceLevelerEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_LEVELER, true)

    fun setVoiceLevelerEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE_LEVELER, on).apply()
    }

    // ── v1.4 连续拼音解析配置 ──
    private const val KEY_CONT_AUTOCOMMIT = "cont_auto_commit"
    private const val KEY_CONT_HIGH = "cont_high_confidence"
    private const val KEY_CONT_ALWAYS = "cont_always_commit"

    /** 高置信直接上屏（默认开；低置信仍走候选/确认交互） */
    fun continuationAutoCommit(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONT_AUTOCOMMIT, true)

    fun setContinuationAutoCommit(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONT_AUTOCOMMIT, on).apply()
    }

    /** 高置信阈值（×100 存储整数，默认 85 即 0.85） */
    fun continuationHighConfidence(ctx: Context): Double =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_CONT_HIGH, 85).toDouble() / 100.0

    fun setContinuationHighConfidence(ctx: Context, v: Double) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CONT_HIGH, (v * 100).toInt().coerceIn(50, 99)).apply()
    }

    /** 产品明确要求"无条件直接上屏"时开启：跳过低置信保护（记日志便于排查） */
    fun continuationAlwaysCommit(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONT_ALWAYS, false)

    fun setContinuationAlwaysCommit(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONT_ALWAYS, on).apply()
    }

    fun fuzzyMask(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_FUZZY, GkFuzzy.DEFAULT)

    fun setFuzzyMask(ctx: Context, mask: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FUZZY, mask).apply()
    }

    fun learning(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LEARNING, true)

    fun setLearning(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LEARNING, on).apply()
    }

    /** 剪贴板历史开关（§5.8；默认开，敏感条目仍不入库 §S-04） */
    fun clipboardEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_CLIPBOARD, true)

    fun setClipboardEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CLIPBOARD, on).apply()
    }

    /** 悬浮键盘路线 B 进阶开关（§5.7；需悬浮窗权限，拒绝时自动降级路线 A） */
    fun floatingRouteB(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_FLOAT_B, false)

    fun setFloatingRouteB(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLOAT_B, on).apply()
    }

    // ── v1.5.3 语音学习记忆（ASR 词 → 用户修正词 + 热度）──

    private const val KEY_VOICE_LEARN = "voice_learn_map"

    data class VoiceLearnEntry(val from: String, val to: String, val heat: Int)

    /** 学习条目，按热度降序 */
    fun voiceLearnEntries(ctx: Context): List<VoiceLearnEntry> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_LEARN, "") ?: ""
        return raw.split('\u0001').filter { it.isNotBlank() }.mapNotNull {
            val p = it.split('\u0002')
            if (p.size == 3) VoiceLearnEntry(p[0], p[1], p[2].toIntOrNull() ?: 1) else null
        }.sortedByDescending { it.heat }
    }

    /** 记录/累加一条学习：ASR 输出 [from] 被用户改成了 [to]，热度 +1 */
    fun addVoiceLearn(ctx: Context, from: String, to: String) {
        if (from.isBlank() || to.isBlank() || from == to) return
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val entries = voiceLearnEntries(ctx).toMutableList()
        val i = entries.indexOfFirst { it.from == from }
        if (i >= 0) entries[i] = entries[i].copy(to = to, heat = entries[i].heat + 1)
        else entries.add(VoiceLearnEntry(from, to, 1))
        // 上限 200 条，超出淘汰热度最低
        while (entries.size > 200) {
            val minIdx = entries.withIndex().minByOrNull { it.value.heat }!!.index
            entries.removeAt(minIdx)
        }
        sp.edit().putString(KEY_VOICE_LEARN,
            entries.joinToString("\u0001") { "${it.from}\u0002${it.to}\u0002${it.heat}" }).apply()
    }

    fun clearVoiceLearn(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_VOICE_LEARN).apply()
    }

    // ── v1.5.4 语音静音自动停（false=持续识别直到手动停）──

    private const val KEY_VOICE_AUTOSTOP = "voice_silence_autostop"

    fun voiceAutoStop(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_AUTOSTOP, false)

    fun setVoiceAutoStop(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOICE_AUTOSTOP, on).apply()
    }

    // ── v1.6.3 语音引擎常驻时长（分钟；0=每次用完即释放；-1=常驻不卸载）──
    // 只看「多久没用语音」，到期自动卸载释放内存，再用时重新加载。
    // 默认 30 分钟（26MB INT8 模型运行占用约 30-50MB，常驻无压力）。

    private const val KEY_VOICE_KEEP_MIN = "voice_keep_minutes"

    fun voiceKeepMinutes(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_VOICE_KEEP_MIN, 30)

    fun setVoiceKeepMinutes(ctx: Context, minutes: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_VOICE_KEEP_MIN, minutes).apply()
    }

    // ── v1.6.8 订阅定时自动更新间隔（分钟；0=关闭，默认 1440=每天）──

    private const val KEY_SUB_AUTO_UPDATE_MIN = "sub_auto_update_min"

    fun subAutoUpdateMinutes(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SUB_AUTO_UPDATE_MIN, 1440)

    fun setSubAutoUpdateMinutes(ctx: Context, minutes: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SUB_AUTO_UPDATE_MIN, minutes).apply()
    }

    // ── v1.5.4 悬浮窗宽度（占屏宽百分比 40..95，默认 72）──

    private const val KEY_FLOAT_W = "floating_width_pct"

    fun floatingWidthPct(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_FLOAT_W, 72)

    fun setFloatingWidthPct(ctx: Context, pct: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FLOAT_W, pct.coerceIn(20, 95)).apply()
    }

    // ── v1.5.8 悬浮模式全局记忆 + 位置记忆 ──

    private const val KEY_FLOAT_ON = "floating_sticky_on"
    private const val KEY_FLOAT_X = "floating_last_x"
    private const val KEY_FLOAT_Y = "floating_last_y"

    /** 悬浮模式全局记忆：开启过悬浮后，任意应用唤起键盘都自动进入悬浮（不按应用区分） */
    fun floatingStickyOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_FLOAT_ON, false)

    fun setFloatingStickyOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLOAT_ON, on).apply()
    }

    /** 上次悬浮位置（gravity END|BOTTOM 坐标系，-1 = 无记录） */
    fun floatingLastPos(ctx: Context): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getInt(KEY_FLOAT_X, -1) to p.getInt(KEY_FLOAT_Y, -1)
    }

    fun setFloatingLastPos(ctx: Context, x: Int, y: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FLOAT_X, x).putInt(KEY_FLOAT_Y, y).apply()
    }

    // ── v1.5.8 字母键上滑标点（Gboard 风格，默认关）──

    private const val KEY_SWIPE_PUNCT = "swipe_punct_enabled"

    fun swipePunct(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SWIPE_PUNCT, false)

    fun setSwipePunct(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SWIPE_PUNCT, on).apply()
    }

    // ── v1.5.8 开发者模式 + 日志 ──

    private const val KEY_DEV_UNLOCKED = "dev_mode_unlocked"
    private const val KEY_DEV_LOG = "dev_log_enabled"

    fun devUnlocked(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DEV_UNLOCKED, false)

    fun setDevUnlocked(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEV_UNLOCKED, on).apply()
    }

    fun devLogEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DEV_LOG, false)

    fun setDevLogEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEV_LOG, on).apply()
    }

    private const val KEY_DEV_LOG_OPEN = "dev_log_open"

    /** 开发者页记忆：上次停留在日志视图（再次进入直接显示日志） */
    fun devLogOpen(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DEV_LOG_OPEN, false)

    fun setDevLogOpen(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEV_LOG_OPEN, on).apply()
    }

    // ── v1.5.8 下文预测 ──

    private const val KEY_NEXT_PREDICT = "next_predict_enabled"

    /** 下文预测（合成区为空时候选栏显示预测词；内置搭配+历史 bigram+实体） */
    fun nextPredict(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEXT_PREDICT, true)

    fun setNextPredict(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NEXT_PREDICT, on).apply()
    }

    // ── 剪贴板气泡"已展示"记录（§5.8；持久化防服务重建后重复弹） ──

    private const val KEY_CLIP_SEEN = "clip_seen_text"
    private const val KEY_CLIP_SEEN_AT = "clip_seen_at"

    /** @return (上次已展示的剪贴板文本, 展示时间戳) */
    fun clipSeen(ctx: Context): Pair<String?, Long> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getString(KEY_CLIP_SEEN, null) to p.getLong(KEY_CLIP_SEEN_AT, 0L)
    }

    fun markClipSeen(ctx: Context, text: String, at: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_CLIP_SEEN, text).putLong(KEY_CLIP_SEEN_AT, at).apply()
    }
}
