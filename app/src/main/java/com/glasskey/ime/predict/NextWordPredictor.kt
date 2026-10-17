package com.glasskey.ime.predict

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.5.8 下文预测（所有输入模式共用）：
 *
 * 双通道结合（调研结论：zhwiki char-bigram 语料库（BSD-3）为台湾正体，不适配简体
 * IME；搜狗搭配库 147MB 且研究授权受限 → 均不采用）：
 *  ① 内置高频搭配表：人工整理的简体中文常用词对/固定搭配 + 表情/符号/数字预测；
 *  ② 用户历史 bigram 学习：每次上屏记录 (上一词, 当前词) 计数并持久化（上限 400），
 *     个性化预测权重高于内置表；
 *  ③ 实体预测：上一词是已学习联系人姓名时，直接预测其电话/邮箱。
 */
object NextWordPredictor {

    // ── 内置高频搭配（简体，人工整理；prev → 按优先级排列的 next）──
    private val BUILTIN: Map<String, List<String>> = mapOf(
        "我" to listOf("们", "是", "的", "在", "想", "要", "不", "很"),
        "你" to listOf("好", "是", "的", "们", "在", "要", "说"),
        "他" to listOf("们", "是", "的", "在", "说", "不"),
        "她" to listOf("是", "的", "在", "说", "们"),
        "我们" to listOf("的", "是", "要", "在", "一起"),
        "你的" to listOf("名字", "电话", "邮箱", "手机"),
        "我的" to listOf("名字", "电话", "邮箱", "手机", "天"),
        "什么" to listOf("时候", "意思", "情况", "问题"),
        "怎么" to listOf("样", "办", "回事", "解决"),
        "可以" to listOf("的", "吗", "了", "吗？"),
        "现在" to listOf("的", "是", "在", "开始", "几点"),
        "就是" to listOf("说", "了", "这样", "现在"),
        "但是" to listOf("我", "他", "现在", "如果"),
        "因为" to listOf("我", "他", "这个", "工作"),
        "所以" to listOf("我", "我们", "现在", "就"),
        "如果" to listOf("你", "我", "可以", "有"),
        "知道" to listOf("了", "吗", "的", "吗？"),
        "觉得" to listOf("这个", "很好", "不错", "可以"),
        "喜欢" to listOf("你", "这个", "的", "吗？"),
        "时候" to listOf("了", "的", "到", "我们"),
        "这个" to listOf("问题", "样子", "方法", "东西"),
        "一个" to listOf("人", "问题", "方法", "小时"),
        "没有" to listOf("问题", "时间", "人", "办法"),
        "不是" to listOf("的", "很", "我说", "这个"),
        "在" to listOf("这", "哪", "家", "吗？", "哪里"),
        "是" to listOf("的", "吗", "不是", "什么"),
        "不" to listOf("是", "要", "知道", "错", "好"),
        "了" to listOf("吗", "的", "。", "！"),
        "好" to listOf("的", "了", "吗", "👍", "！"),
        "谢谢" to listOf("你", "🙏", "！"),
        "哈哈" to listOf("😂", "，", "！"),
        "晚安" to listOf("🌙", "，", "！"),
        "再见" to listOf("👋", "！"),
        "加油" to listOf("💪", "！"),
        "生日快乐" to listOf("🎂", "🎉", "！"),
        "新年快乐" to listOf("🧧", "🎆", "！"),
        "好的" to listOf("，", "👍", "。"),
        "电话" to listOf("号码", "是", "。"),
        "邮箱" to listOf("是", "地址", "。"),
        "微信" to listOf("号", "是", "上"),
        "手机" to listOf("号", "上", "是"),
        "第" to listOf("一", "二", "三", "1", "2", "3"),
        "1" to listOf("0", "1", "2", "3", "4", "5"),
        "2" to listOf("0", "1", "2", "3", "4", "5"),
    )

    /**
     * 预测下一词：
     * ① 联系人实体（姓名 → 电话/邮箱）最高优先；
     * ② 用户历史 bigram（按热度）；
     * ③ 内置搭配表。
     * v1.6.0：移除 STARTERS（无上文时不再凭空预测"你好"等起始词——
     * 输入框一个字都没有就预测词没有依据，且不强行改频率/热度）。
     */
    fun predict(ctx: Context, prev: String, limit: Int = 8): List<String> {
        if (prev.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        // ① 实体预测：姓名 → 电话/邮箱
        EntityStore.byName(ctx, prev)?.let { c ->
            c.phone?.let { out.add(it) }
            c.email?.let { out.add(it) }
        }
        // ② 用户历史
        GkBigram.top(ctx, prev).forEach { out.add(it.first) }
        // ③ 内置
        BUILTIN[prev]?.forEach { out.add(it) }
        return out.toList().take(limit)
    }

    private val lastPendingName = ThreadLocal.withInitial { "" }

    /**
     * 上屏观察：学习 bigram + 实体抽取/关联。
     * @param prev 上一 token（可为空）
     * @param cur 本次上屏文本
     */
    fun observe(ctx: Context, prev: String, cur: String) {
        val t = cur.trim()
        if (t.isEmpty() || t.length > 20) return
        if (prev.isNotEmpty() && prev != t) GkBigram.add(ctx, prev, t)
        // 实体抽取：电话/邮箱/姓名，窗口关联（本 token 带电话/邮箱时挂到最近姓名）
        val contact = EntityLearner.extract(t)
        if (contact != null) {
            val pending = lastPendingName.get()
            EntityStore.upsert(ctx, pending.ifEmpty { contact.name ?: "" },
                contact.phone, contact.email)
        }
        if (contact?.name != null) lastPendingName.set(contact.name)
        else if (contact != null) lastPendingName.set("")
        else if (EntityLearner.looksLikeName(t)) lastPendingName.set(t)
    }
}

/** 用户历史 bigram（SharedPreferences 持久化，上限 400 条热度淘汰） */
private object GkBigram {
    private const val KEY = "next_bigram_map"

    internal fun top(ctx: Context, prev: String): List<Pair<String, Int>> {
        return all(ctx).filter { it.first == prev }
            .sortedByDescending { it.third }.take(4).map { it.second to it.third }
    }

    internal fun add(ctx: Context, prev: String, cur: String) {
        val sp = ctx.getSharedPreferences("glasskey_prefs", Context.MODE_PRIVATE)
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.first == prev && it.second == cur }
        if (i >= 0) list[i] = Triple(prev, cur, list[i].third + 1)
        else list.add(Triple(prev, cur, 1))
        while (list.size > 400) {
            val minIdx = list.withIndex().minByOrNull { it.value.third }!!.index
            list.removeAt(minIdx)
        }
        sp.edit().putString(KEY, list.joinToString("\u0001") {
            "${it.first}\u0002${it.second}\u0002${it.third}"
        }).apply()
    }

    private fun all(ctx: Context): List<Triple<String, String, Int>> {
        val raw = ctx.getSharedPreferences("glasskey_prefs", Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return raw.split('\u0001').filter { it.isNotBlank() }.mapNotNull {
            val p = it.split('\u0002')
            if (p.size == 3) Triple(p[0], p[1], p[2].toIntOrNull() ?: 1) else null
        }
    }

    /** 设置页展示用：全部条目按热度降序 */
    fun allForSettings(ctx: Context): List<Triple<String, String, Int>> =
        all(ctx).sortedByDescending { it.third }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("glasskey_prefs", Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}

/** 设置页/清空入口（内部对象委托） */
object NextWordStats {
    fun bigrams(ctx: Context): List<Triple<String, String, Int>> = GkBigram.allForSettings(ctx)
    fun clearBigrams(ctx: Context) = GkBigram.clear(ctx)
    fun contacts(ctx: Context): List<EntityStore.Contact> = EntityStore.all(ctx)
    fun deleteContact(ctx: Context, name: String) = EntityStore.delete(ctx, name)
    fun clearContacts(ctx: Context) = EntityStore.clear(ctx)
}

/** 联系人实体持久化（JSON in SharedPreferences；仅存本机） */
object EntityStore {

    data class Contact(
        val name: String, val phone: String?, val email: String?, val heat: Int,
    )

    private const val KEY = "learn_contacts"

    fun all(ctx: Context): List<Contact> {
        val raw = ctx.getSharedPreferences("glasskey_prefs", Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Contact(
                    o.optString("name"), o.optString("p", "").ifEmpty { null },
                    o.optString("e", "").ifEmpty { null }, o.optInt("h", 1))
            }.sortedByDescending { it.heat }
        } catch (_: Throwable) { emptyList() }
    }

    fun byName(ctx: Context, name: String): Contact? =
        all(ctx).firstOrNull { it.name == name }

    /** 新增/合并：同姓名合并（字段非空覆盖），热度 +1 */
    fun upsert(ctx: Context, name: String, phone: String?, email: String?) {
        if (name.isBlank() && phone == null && email == null) return
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.name == name }
        if (i >= 0) {
            val c = list[i]
            list[i] = c.copy(
                phone = phone ?: c.phone, email = email ?: c.email, heat = c.heat + 1)
        } else {
            list.add(Contact(name.ifEmpty { "未知名" }, phone, email, 1))
        }
        while (list.size > 50) {
            val minIdx = list.withIndex().minByOrNull { it.value.heat }!!.index
            list.removeAt(minIdx)
        }
        save(ctx, list)
    }

    fun delete(ctx: Context, name: String) = save(ctx, all(ctx).filter { it.name != name })

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<Contact>) {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().apply {
            put("name", c.name); put("p", c.phone ?: ""); put("e", c.email ?: ""); put("h", c.heat)
        })
        ctx.getSharedPreferences("glasskey_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}

/** 实体启发式抽取（手机号/邮箱/中文姓名） */
internal object EntityLearner {

    private val PHONE = Regex("""1[3-9]\d{9}""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val NAME_INTRO = Regex("""(?:我叫|我是|联系|找|找一下)([\u4e00-\u9fa5]{2,3})""")
    private val NAME_TITLE = Regex("""([\u4e00-\u9fa5]{2,3})(?:先生|女士|同学|老师|总)""")

    data class Result(val name: String?, val phone: String?, val email: String?)

    /** 从提交文本抽取实体；无任何命中返回 null */
    fun extract(text: String): Result? {
        val phone = PHONE.find(text)?.value
        val email = EMAIL.find(text)?.value
        val name = NAME_INTRO.find(text)?.groupValues?.get(1)
            ?: NAME_TITLE.find(text)?.groupValues?.get(1)
        return if (phone == null && email == null && name == null) null
        else Result(name, phone, email)
    }

    /** 2-3 个汉字（姓氏表开头）→ 疑似姓名（作为待关联主体） */
    private val SURNAMES = "王李张刘陈杨黄赵吴周徐孙马朱胡郭何林罗高郑梁谢宋唐许韩冯邓曹彭曾肖田董潘袁蔡蒋余于杜叶程魏苏吕丁任卢姚沈钟姜崔谭陆范汪廖石金韦贾夏付方邹熊白孟秦邱侯江尹薛闫段雷龙黎史陶贺毛郝顾龚邵万钱严赖覃洪武莫孔汤向常温康施文牛樊葛邢安齐易乔伍庞颜倪庄聂章鲁岳翟殷詹申欧耿关兰焦俞左柳甘祝包宁尚符舒阮柯纪梅童凌毕单季裴霍涂成苗谷盛曲翁冉骆蓝路游辛靳欧管柴蒙鲍华喻祁蒲房滕屈饶解牟艾尤阳时穆农司卓古吉缪简车项连芦麦褚娄窦戚岑景党宫费卜冷晏席卫米柏宗瞿桂全佟应臧闵苟邬边卞姬师和仇栾隋商刁沙荣巫寇桑郎甄丛仲虞敖巩明佘池查麻苑迟邝官封谈鞠"
    fun looksLikeName(text: String): Boolean {
        if (text.length !in 2..3 || text.any { it !in '\u4e00'..'\u9fff' }) return false
        return text[0] in SURNAMES
    }
}
