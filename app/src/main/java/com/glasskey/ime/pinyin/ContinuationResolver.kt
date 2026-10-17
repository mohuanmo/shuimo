package com.glasskey.ime.pinyin

/**
 * 连续拼音输入解析（v1.4，重点需求：连续字母输入判断）
 *
 * 分工（混合方案，不依赖语法模型/大模型）：
 *   第一层：[ContinuationResolver] 拼音切分（DP lattice）+ 词库/用户词库/历史修正 → 候选生成
 *   第二层：打分排序（切分代价 + 词频 + 用户词 Boost + 历史 Boost）→ 高置信结果
 *   第三层：主模型/手写模型不参与本路径（可插拔，见服务层）；失败回退到现有候选/确认机制
 *
 * 支持形态（长度不写死，1..64 字母均可）：
 *   - 全拼连打：nihao → 你好
 *   - 首字母连打：nh → 你好（若词库有）
 *   - 前缀字母：nih → 你好（h 是 hao 的前缀）
 *   - 混合：nhao / nihao 首末混合
 *   - 中英数字混合：由服务层判断（含数字/大写/英文词时不进入本解析，走引擎原文上屏）
 *
 * 实时优化：
 *   - 单次解析 O(n × 音节长度上限) DP，无回溯；实测 24 字母 < 1ms（见延迟测试）；
 *   - 服务层按输入串做 LRU 缓存 + 350ms 防抖（打字过程中只出候选，停顿才判定上屏）；
 *   - 候选剪枝：每个切分点只保留 top [Config.maxPaths] 条路径、每路径 top 词。
 */
class ContinuationResolver(
    private val lexicon: Lexicon,
    private val config: Config = Config(),
) {

    data class Config(
        /** 参与排序的最大切分路径数 */
        val maxPaths: Int = 8,
        /** 输出候选上限（统一 30） */
        val candidateLimit: Int = 30,
        /** 高置信阈值：≥ 该值允许直接上屏（可配置，见 GkPrefs） */
        val highConfidence: Double = 0.85,
        /** 用户词 Boost */
        val userBoost: Double = 2.0,
        /** 历史/学习词 Boost */
        val historyBoost: Double = 1.4,
    )

    /** 词库数据源（服务层适配：基础词 + 用户词 + 学习历史 + 历史修改） */
    interface Lexicon {
        /** @return 候选词列表（word to 无声调拼音, 词频 0..1000） */
        fun wordsFor(pinyinPattern: String): List<Triple<String, String, Int>>

        /** 历史修改记录：用户上次把 [pattern] 的首选改成了什么 */
        fun historyTop(pattern: String): String? = null

        /**
         * v1.6.0 词对转移加成（bigram log 概率的简化映射）：
         * [prev] 后接 [next] 的可信度 0..1，0 = 无数据。
         * 由服务层实现接语料词对表（assets 懒加载）+ 用户历史 bigram。
         */
        fun pairBoost(prev: String, next: String): Double = 0.0
    }

    /** 解析结果 */
    data class Result(
        val input: String,
        val segmentation: List<String>,      // 切分后的音节/缩写 token
        val isMixedForm: Boolean,            // 是否含首字母/前缀（全拼=false）
        val candidates: List<String>,        // ≤ candidateLimit，按分排序
        val confidence: Double,              // 0..1
        val highConfidence: Boolean,
    )

    fun resolve(input: String): Result? {
        // 中英数字混合（含数字/大写/非字母）不进本解析：服务层走引擎/原文上屏路径
        if (input.any { it !in 'a'..'z' }) return null
        val s = input
        if (s.length < 2) return null
        val paths = segment(s).take(config.maxPaths)
        if (paths.isEmpty()) return null
        val best = paths[0]
        val isMixed = best.tokens.any { it.type != TokenType.FULL }

        // —— 候选生成 + 打分 ——
        data class Cand(val word: String, val score: Double, val pathPenalty: Double)
        val cands = LinkedHashMap<String, Cand>()
        val memo = HashMap<String, List<Triple<String, String, Int>>>()  // v1.5.8 词查询去重
        fun query(pattern: String): List<Triple<String, String, Int>> =
            memo.getOrPut(pattern) { lexicon.wordsFor(pattern).take(6) }
        // 历史修改记录置顶候选（用户上次怎么改的，这次先给什么）
        lexicon.historyTop(s)?.let { top -> cands[top] = Cand(top, 14.0, 0.0) }
        paths.forEachIndexed { pi, path ->
            val pathPenalty = path.cost - best.cost + pi * 0.15
            val words = generateWords(path, ::query)
            for ((w, base) in words) {
                val score = base - pathPenalty
                val old = cands[w]
                if (old == null || score > old.score) cands[w] = Cand(w, score, pathPenalty)
            }
            // v1.5.8 长句组合：≥3 token 的路径做多词序列 DP（8 字长句/短句组合），
            // 每段查词库（支持全拼/首字母/前缀混拼 token），按词频与段数打分
            if (path.tokens.size >= 3) {
                for ((sentence, sc) in composeSentences(path, ::query)) {
                    val score = sc - pathPenalty
                    val old = cands[sentence]
                    if (old == null || score > old.score) cands[sentence] = Cand(sentence, score, pathPenalty)
                }
            }
        }
        // 用户词/历史 Boost 与重排
        val historyTop = lexicon.historyTop(s)
        val ranked = cands.values.map { c ->
            var sc = c.score
            val py = userPinyinOf(c.word)
            if (py != null) sc += config.userBoost
            if (c.word == historyTop) sc += config.historyBoost
            c.word to sc
        }.sortedByDescending { it.second }
            .take(config.candidateLimit)
            .map { it.first }

        // —— 置信度：最佳路径占比 + 全拼覆盖率 + 词库命中 ——
        val totalCost = paths.sumOf { it.cost }.coerceAtLeast(1e-9)
        val margin = 1.0 - (best.cost / totalCost) * paths.size.coerceAtLeast(1) * 0.5
        val fullRatio = best.tokens.count { it.type == TokenType.FULL }.toDouble() /
            best.tokens.size.coerceAtLeast(1)
        val lexHit = ranked.isNotEmpty()
        val confidence = (0.35 * margin.coerceIn(0.0, 1.0) +
            0.4 * fullRatio + 0.25 * (if (lexHit) 1.0 else 0.35))
            .coerceIn(0.0, 1.0)
        return Result(
            input = s,
            segmentation = best.tokens.map { it.text },
            isMixedForm = isMixed,
            candidates = ranked,
            confidence = confidence,
            highConfidence = confidence >= config.highConfidence && ranked.isNotEmpty(),
        )
    }

    // ────────────── 第一层：切分（DP lattice）──────────────

    internal enum class TokenType { FULL, INITIAL, PREFIX }

    internal data class Token(val text: String, val type: TokenType)

    internal data class Path(val tokens: List<Token>, val cost: Double)

    /** 单个位置的最大音节长度匹配窗口 */
    private val maxSylLen = Syllables.all.maxOf { it.length }

    /**
     * DP 切分：每步可消费
     *  - 完整音节（代价 1.0）
     *  - 声母（首字母，代价 2.2）
     *  - 音节前缀（2..len-1 字母，代价 1.4 + 0.3*(len-实际长度)）
     * 返回按代价升序的路径（beam 剪枝）。
     */
    internal fun segment(s: String): List<Path> {
        val n = s.length
        // buckets[i] = 终止于位置 i 的路径（标准 DP，不做跨步覆盖）
        val buckets = Array(n + 1) { mutableListOf<Path>() }
        buckets[0].add(Path(emptyList(), 0.0))
        for (i in 0 until n) {
            val cur = buckets[i].sortedBy { it.cost }.take(config.maxPaths)
            buckets[i] = cur.toMutableList()
            if (cur.isEmpty()) continue
            for (p in cur) {
                val maxLen = minOf(maxSylLen, n - i)
                for (len in maxLen downTo 1) {
                    val frag = s.substring(i, i + len)
                    val cand = when {
                        len == 1 && Syllables.initialsSet.contains(frag) ->
                            Path(p.tokens + Token(frag, TokenType.INITIAL), p.cost + 2.2)
                        Syllables.set.contains(frag) ->
                            Path(p.tokens + Token(frag, TokenType.FULL), p.cost + 1.0)
                        len > 1 && Syllables.hasPrefix(frag) ->
                            Path(p.tokens + Token(frag, TokenType.PREFIX),
                                p.cost + 1.4 + 0.3 * (maxLen - len))
                        else -> null
                    } ?: continue
                    buckets[i + len].add(cand)
                }
            }
        }
        return buckets[n].sortedBy { it.cost }
    }

    // ────────────── 候选生成 ──────────────

    

    /** 按路径生成词候选：整词匹配 + 逐 token 兜底单字 */
    private fun generateWords(path: Path, query: (String) -> List<Triple<String, String, Int>>): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        // 词库整词/模式匹配（含用户词、历史）。
        // 首选 pattern 为字面切分；补充一个"尾 token 按前缀展开"的变体
        //（如 niha → [ni, ha] 亦可匹配 ni hao），覆盖切分歧义。
        val pattern0 = path.tokens.joinToString(" ") { t ->
            when (t.type) {
                TokenType.FULL -> t.text
                else -> "${t.text}*"
            }
        }
        val pattern1 = if (path.tokens.size >= 2) {
            path.tokens.dropLast(1).joinToString(" ") { t ->
                when (t.type) { TokenType.FULL -> t.text; else -> "${t.text}*" }
            } + " ${path.tokens.last().text}*"
        } else pattern0
        for (pattern in linkedSetOf(pattern0, pattern1)) {
            query(pattern).forEach { (w, _, freq) ->
                out.add(w to 8.0 + freq / 200.0)
            }
        }
        // 2) 全拼路径：相邻两 token 组词（nihao → nihao 整串已在词库查询中覆盖）
        // 3) 单字兜底：每个 token 取常用字（前缀/缩写 token 展开多字）
        for (t in path.tokens) {
            val chars = when (t.type) {
                TokenType.FULL -> Syllables.commonChars[t.text].orEmpty()
                else -> {
                    // 前缀/首字母：展开该声母下所有音节的常用字（剪枝：每 token ≤ 12 字）
                    val sb = StringBuilder()
                    for ((syl, chars2) in Syllables.commonChars) {
                        if (syl.startsWith(t.text)) {
                            for (c in chars2) {
                                if (sb.length >= 12) break
                                if (sb.indexOf(c) < 0) sb.append(c)
                            }
                        }
                    }
                    sb.toString()
                }
            }
            for (c in chars.take(8)) {
                out.add(c.toString() to 4.0)
            }
        }
        return out
    }

    /**
     * v1.5.8 长句组合：对路径 token 序列做多词 DP——
     * best[i] = 覆盖前 i+1 个 token 的 top3 句子（句子, 得分）；
     * 每步尝试 1..4 token 的段，段模式查词库取 top2 词，
     * 得分 = 词频/200 − 段数×0.35（偏好少段长词）。
     * 支持 FULL/INITIAL/PREFIX 混拼段（词库模式匹配语义）。
     */
    private fun composeSentences(
        path: Path, query: (String) -> List<Triple<String, String, Int>>,
    ): List<Pair<String, Double>> {
        val n = path.tokens.size
        data class Node(val text: String, val score: Double, val lastWord: String)
        val best = Array(n + 1) { mutableListOf<Node>() }
        best[0].add(Node("", 0.0, ""))
        for (i in 1..n) {
            for (len in 1..minOf(4, i)) {
                val seg = path.tokens.subList(i - len, i)
                val pattern = seg.joinToString(" ") { t ->
                    when (t.type) { TokenType.FULL -> t.text; else -> "${t.text}*" }
                }
                val words = query(pattern)
                if (words.isEmpty()) continue
                for (prev in best[i - len].take(5)) {
                    for ((w, _, freq) in words.take(6)) {
                        // 去重保护：同字接续（避免"的的"类拼接噪声）
                        if (prev.text.isNotEmpty() && prev.text.last() == w.first() &&
                            prev.text.takeLast(2) == w.takeLast(2)) continue
                        // v1.5.9 打分：词长加成 + 单字降权 + 段惩罚——
                        // 否则高慢单字链（每字 freq/200）总分离过两词句，
                        // 组句候选永远进不了前列（实测 0% 的根因之一）
                        val unit = if (w.length == 1) freq / 1000.0 + 1.0
                        else freq / 200.0 + w.length * 2.0
                        // v1.6.0 词对转移分：语料 bigram + 用户历史 bigram
                        //（解决"开时讨论 20937 > 开始讨论 17500"的同音词频歧义）
                        val boost = if (prev.lastWord.isEmpty()) 0.0
                        else lexicon.pairBoost(prev.lastWord, w) * 15.0
                        best[i].add(Node(prev.text + w, prev.score + unit - 1.2 + boost, w))
                    }
                }
            }
            // beam 剪枝（允许某位置无候选——单音节段可能词库无单字，不断链）
            if (best[i].size > 6) {
                best[i].sortedByDescending { it.score }
                    .distinctBy { it.text }.let { lst ->
                        best[i].clear(); best[i].addAll(lst.take(6))
                    }
            }
        }
        return best[n].map { it.text to (it.score + 3.0) }  // 组句整体加成（长句需求优先）
    }

    private fun userPinyinOf(word: String): String? =
        lexicon.wordsFor(word).firstOrNull { it.first == word }?.second

    companion object {
        /** 统一候选上限（普通词/单字/手写共用配置，默认 30） */
        const val CANDIDATE_LIMIT = 30
    }
}

/**
 * 拼音音节表（无调，与 core/src/pinyin/syllable_table.cpp 同源精简版；
 * 声母表 + 常用字映射用于单字兜底与首字母展开）。
 */
object Syllables {
    val all: List<String> = """
        a ai an ang ao
        ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
        ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
        da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
        e ei en er
        fa fan fang fei fen feng fo fou fu
        ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
        ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
        ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
        ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo
        la lai lan lang lao le lei leng lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lue
        ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
        na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nue
        o ou
        pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
        qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
        ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
        sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
        ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
        wa wai wan wang wei wen weng wo wu
        xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
        ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
        za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val set: Set<String> = all.toSet()

    val initialsSet: Set<String> =
        "b p m f d t n l g k h j q x zh ch sh r z c s y w".split(" ").toSet()

    fun hasPrefix(frag: String): Boolean =
        frag.length > 1 && all.any { it.startsWith(frag) }

    /**
     * v1.5.8：无分隔拼音串 → 音节列表（贪心最长匹配，4 字以上失败返回 null）。
     * 用于 voice_lexicon.tsv（"nihao" 无空格）接入连续拼音词库。
     */
    fun greedySplit(py: String): List<String>? {
        if (py.isEmpty()) return null
        val out = ArrayList<String>(4)
        var i = 0
        while (i < py.length) {
            var matched = false
            for (len in minOf(6, py.length - i) downTo 1) {
                val frag = py.substring(i, i + len)
                if (set.contains(frag)) {
                    out.add(frag); i += len; matched = true; break
                }
            }
            if (!matched) return null
            if (out.size > 8) return null
        }
        return out
    }

    /** 高频音节 → 常用字（单字兜底；覆盖日常输入即可，完整集由引擎词典提供） */
    val commonChars: Map<String, String> = mapOf(
        "a" to "啊阿", "ai" to "爱", "an" to "安按", "ang" to "昂", "ao" to "奥",
        "ba" to "吧把八", "bai" to "百白", "ban" to "办半", "bang" to "帮", "bao" to "报宝包",
        "bei" to "北被被", "ben" to "本", "bi" to "比必", "bian" to "边变", "biao" to "表",
        "bie" to "别", "bin" to "宾", "bing" to "并病", "bo" to "波播", "bu" to "不部步",
        "ca" to "擦", "cai" to "才菜采", "can" to "参", "ce" to "测", "ceng" to "层",
        "cha" to "查茶差", "chan" to "产", "chang" to "常长场", "chao" to "超朝",
        "che" to "车", "chen" to "陈沉", "cheng" to "成城程", "chi" to "吃持尺",
        "chong" to "重充", "chu" to "出处除", "chuan" to "传川", "chuang" to "窗创",
        "chui" to "吹", "chun" to "春", "ci" to "此次词", "cong" to "从", "cu" to "粗",
        "cui" to "催", "cun" to "村", "cuo" to "错",
        "da" to "大打达", "dai" to "带代", "dan" to "但单蛋", "dang" to "当党",
        "dao" to "到道", "de" to "的得地", "deng" to "等灯", "di" to "地第低",
        "dian" to "点电店", "diao" to "调", "die" to "爹跌", "ding" to "定顶",
        "dong" to "动东冬", "dou" to "都斗", "du" to "读度都", "duan" to "段短",
        "dui" to "对队", "dun" to "顿", "duo" to "多朵",
        "e" to "而额", "er" to "而二儿",
        "fa" to "发法", "fan" to "反饭翻", "fang" to "方放房", "fei" to "非飞费",
        "fen" to "分份", "feng" to "风封丰", "fu" to "服付夫复福",
        "gai" to "该改盖", "gan" to "感干敢", "gang" to "刚港", "gao" to "高告",
        "ge" to "个各哥", "gei" to "给", "gen" to "跟根", "geng" to "更",
        "gong" to "工公共功", "gou" to "够狗沟", "gu" to "古顾故", "gua" to "挂瓜",
        "guai" to "怪", "guan" to "关管观", "guang" to "光广", "gui" to "贵规",
        "guo" to "过国果",
        "ha" to "哈", "hai" to "还海", "han" to "汉含寒", "hao" to "好号",
        "he" to "和何河", "hen" to "很", "heng" to "横衡", "hong" to "红宏",
        "hou" to "后候", "hu" to "户湖护", "hua" to "话花化", "huai" to "坏怀",
        "huan" to "还换环", "huang" to "黄荒", "hui" to "会回汇", "hun" to "婚混",
        "huo" to "或活火货",
        "ji" to "机几级记家", "jia" to "家加价假", "jian" to "见间件简建",
        "jiang" to "将江讲", "jiao" to "交教叫", "jie" to "接节结解界",
        "jin" to "进金近", "jing" to "经京精静", "jiu" to "就九久", "ju" to "局句举",
        "jue" to "决定", "jun" to "军",
        "ka" to "卡", "kai" to "开", "kan" to "看", "kang" to "抗", "kao" to "考",
        "ke" to "可课刻", "ken" to "肯", "kong" to "空恐", "kou" to "口",
        "ku" to "苦库", "kuai" to "快块", "kuan" to "宽款", "kuang" to "况",
        "kui" to "亏", "kun" to "困", "kuo" to "扩",
        "la" to "拉啦", "lai" to "来", "lan" to "蓝兰篮", "lao" to "老劳",
        "le" to "了乐", "lei" to "类累", "leng" to "冷", "li" to "里理力利立",
        "lian" to "连联脸", "liang" to "两量亮", "liao" to "聊料", "lie" to "列烈",
        "lin" to "林", "ling" to "领零灵", "liu" to "六留流", "long" to "龙隆",
        "lou" to "楼", "lu" to "路录露", "lv" to "绿旅律", "luan" to "乱", "lun" to "论轮",
        "luo" to "落罗",
        "ma" to "马吗妈码", "mai" to "买卖迈", "man" to "满慢", "mang" to "忙盲",
        "mao" to "毛猫冒", "me" to "么", "mei" to "没美每妹", "men" to "门们",
        "mi" to "米密迷", "mian" to "面棉免", "miao" to "秒苗", "ming" to "名明命",
        "mo" to "模末莫", "mou" to "某谋", "mu" to "木目母幕",
        "na" to "那拿", "nai" to "奶耐", "nan" to "南难男", "nao" to "脑闹",
        "ne" to "呢", "nei" to "内", "neng" to "能", "ni" to "你年尼",
        "nian" to "年念", "niang" to "娘", "nin" to "您", "ning" to "宁凝",
        "niu" to "牛", "nong" to "农弄", "nu" to "女努", "nuo" to "诺挪",
        "ou" to "偶欧",
        "pa" to "怕拍", "pai" to "排派", "pan" to "盘判", "pang" to "旁胖",
        "pao" to "跑炮泡", "pei" to "配陪", "peng" to "朋碰碰", "pi" to "皮批",
        "pian" to "片篇偏", "ping" to "平评凭", "po" to "破婆", "pu" to "普铺",
        "qi" to "起其七气期", "qian" to "前千钱签", "qiang" to "强墙",
        "qiao" to "桥巧悄", "qie" to "切且", "qin" to "亲琴勤", "qing" to "请青清情轻",
        "qiu" to "求球秋", "qu" to "去区取", "quan" to "全权劝", "que" to "确却缺",
        "qun" to "群裙",
        "ran" to "然染", "rang" to "让", "rao" to "绕扰", "re" to "热",
        "ren" to "人认任", "reng" to "扔仍", "ri" to "日", "rong" to "容融",
        "rou" to "肉", "ru" to "如入", "ruan" to "软", "rui" to "瑞锐",
        "run" to "润", "ruo" to "若弱",
        "sa" to "撒萨", "sai" to "赛赛", "san" to "三散", "se" to "色",
        "sha" to "杀沙傻", "shan" to "山善", "shang" to "上商", "shao" to "少烧",
        "she" to "社设蛇", "shei" to "谁", "shen" to "什深神身", "sheng" to "生声胜升",
        "shi" to "是时十使事实", "shou" to "手收受", "shu" to "书数术树输",
        "shua" to "刷", "shuai" to "帅摔", "shui" to "水谁", "shun" to "顺",
        "shuo" to "说", "si" to "四思死似", "song" to "送松", "su" to "素速诉",
        "suan" to "算酸", "sui" to "岁随", "sun" to "孙损", "suo" to "所锁",
        "ta" to "他她它塔", "tai" to "台太抬", "tan" to "谈坦探", "tang" to "糖堂汤",
        "tao" to "套桃逃", "te" to "特", "ti" to "提题体替", "tian" to "天田甜",
        "tiao" to "条跳", "tie" to "铁贴", "ting" to "听厅停庭", "tong" to "同通统痛",
        "tou" to "头投透", "tu" to "图突土", "tuan" to "团", "tui" to "推退腿",
        "tun" to "吞", "tuo" to "脱托",
        "wa" to "娃挖瓦袜", "wai" to "外", "wan" to "万完晚玩", "wang" to "王网往忘",
        "wei" to "为位未围伟", "wen" to "文问闻稳", "weng" to "翁", "wo" to "我卧",
        "wu" to "无五物武务误",
        "xi" to "西息喜系细", "xia" to "下夏侠", "xian" to "先现线显县",
        "xiang" to "想相向香箱", "xiao" to "小笑消校", "xie" to "些写谢鞋血",
        "xin" to "心新信", "xing" to "行性姓名星", "xiong" to "兄雄", "xiu" to "修秀",
        "xu" to "需须续许", "xuan" to "选宣传", "xue" to "学雪血", "xun" to "训寻询",
        "ya" to "呀押牙雅压", "yan" to "言研眼演严验", "yang" to "样阳羊洋养",
        "yao" to "要药摇邀腰", "ye" to "也夜叶业", "yi" to "一以易义意义已",
        "yin" to "因引音银", "ying" to "应英影营迎", "yong" to "用永勇",
        "you" to "有又由右游邮", "yu" to "与余于雨语育预", "yuan" to "原员元园远院",
        "yue" to "月越约乐阅", "yun" to "运云允",
        "za" to "杂砸", "zai" to "在再载", "zan" to "咱暂", "zang" to "脏葬",
        "zao" to "早造遭", "ze" to "则择责", "zen" to "怎", "zeng" to "增曾",
        "zha" to "扎渣炸", "zhai" to "宅窄", "zhan" to "战站展占", "zhang" to "张章长涨",
        "zhao" to "找照着", "zhe" to "这着折", "zhen" to "真针阵震", "zheng" to "正整政证争",
        "zhi" to "之只知制指至志", "zhong" to "中重种众", "zhou" to "周州洲",
        "zhu" to "住主注著助猪", "zhuan" to "转专赚", "zhuang" to "装壮状",
        "zhui" to "追", "zhun" to "准", "zhuo" to "桌捉", "zi" to "自字子资紫",
        "zong" to "总纵宗", "zou" to "走奏", "zu" to "足组祖族", "zuan" to "钻",
        "zui" to "最嘴", "zun" to "尊遵", "zuo" to "做作坐左昨",
    )
}
