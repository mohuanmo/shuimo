package com.glasskey.ime.engine

/**
 * JNI 声明（§8.1 C ABI 的 Kotlin 映射）。
 * 句柄为 0 表示引擎未创建（词典缺失/损坏）。
 */
object GkNative {
    init {
        System.loadLibrary("glasskey")
    }

    external fun nativeVersion(): String
    external fun nativeCreate(
        dictPath: String, latinPath: String?, fuzzyMask: Int,
        beamWidth: Int, maxCandidates: Int, enableLearning: Boolean
    ): Long

    external fun nativeDestroy(handle: Long)
    external fun nativeSetLayout(handle: Long, layout: Int)
    external fun nativeSetFuzzy(handle: Long, mask: Int)
    external fun nativeSetLearning(handle: Long, enabled: Boolean)
    external fun nativeProcessKey(handle: Long, code: Int, action: Int, meta: Int): Int
    external fun nativeBackspace(handle: Long): Int
    external fun nativeReset(handle: Long)
    external fun nativeGetInput(handle: Long): String

    /** 自动分词显示串（v1.2）：音节边界空格呈现、手动锚点紧贴前段；每击键重算 */
    external fun nativeGetSegmentedInput(handle: Long): String

    /** 手动分词（v1.2）：在首个未锚定音节边界插入分隔符并锚定；成功返回 1 */
    external fun nativeInsertManualSeparator(handle: Long): Int

    /** 设置上文（v1.3）：光标前已上屏文本，供候选重排（结合前文排列候选） */
    external fun nativeSetContext(handle: Long, before: String?)
    external fun nativeCandidateCount(handle: Long): Int
    external fun nativeGetCandidate(handle: Long, index: Int): String
    external fun nativeGetCandidatePinyin(handle: Long, index: Int): String

    /** 提交候选 → [上屏文本, 拼音]；失败返回 null */
    external fun nativeCommit(handle: Long, index: Int): Array<String>?
    external fun nativeT9PinyinCount(handle: Long): Int
    external fun nativeGetT9Pinyin(handle: Long, index: Int): String
    external fun nativeLearn(handle: Long, word: String, pinyin: String, source: Int)
    external fun nativeLoadUserWords(handle: Long, blob: ByteArray)
    external fun nativeSessionEnd(handle: Long)
}

/** 布局常量（gk_types.h Layout） */
object GkLayout {
    const val PINYIN_26 = 0
    const val PINYIN_9 = 1
    const val LATIN = 2
}

/** 模糊音位掩码（gk_types.h FuzzyMask） */
object GkFuzzy {
    const val NONE = 0
    const val ZH_Z = 1 shl 0
    const val CH_C = 1 shl 1
    const val SH_S = 1 shl 2
    const val AN_ANG = 1 shl 3
    const val EN_ENG = 1 shl 4
    const val IN_ING = 1 shl 5
    const val N_L = 1 shl 6
    const val F_H = 1 shl 7
    const val DEFAULT = ZH_Z or CH_C or SH_S or AN_ANG or EN_ENG or IN_ING or N_L or F_H
}

/** 学习来源（gk_types.h LearnSource） */
object GkLearnSource {
    const val SELECT = 0
    const val SENTENCE = 3
}

/**
 * 引擎 Kotlin 门面：持有原生句柄，负责词典部署与生命周期。
 * 词典文件由 assets/dict/ 首次启动拷贝到 filesDir（只读装载，单向持久化 §4.5）。
 */
class GkEngine(
    private val dictPath: String,
    private val latinPath: String?,
    var fuzzyMask: Int = GkFuzzy.DEFAULT,
    private val beamWidth: Int = 10,
    private val maxCandidates: Int = 10,
    var learningEnabled: Boolean = true,
) {
    var handle: Long = 0L
        private set

    val ready: Boolean get() = handle != 0L

    fun open(): Boolean {
        if (handle != 0L) return true
        handle = GkNative.nativeCreate(
            dictPath, latinPath, fuzzyMask, beamWidth, maxCandidates, learningEnabled
        )
        return handle != 0L
    }

    fun close() {
        if (handle != 0L) {
            GkNative.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun setLayout(layout: Int) = GkNative.nativeSetLayout(handle, layout)
    fun setFuzzy(mask: Int) {
        fuzzyMask = mask
        GkNative.nativeSetFuzzy(handle, mask)
    }

    fun setLearning(on: Boolean) {
        learningEnabled = on
        GkNative.nativeSetLearning(handle, on)
    }

    fun processKey(code: Int): Int = GkNative.nativeProcessKey(handle, code, 0, 0)
    fun backspace(): Int = GkNative.nativeBackspace(handle)
    fun reset() = GkNative.nativeReset(handle)
    fun input(): String = GkNative.nativeGetInput(handle)

    /** 自动分词显示串（LATIN 模式返回原文） */
    fun segmentedInput(): String = GkNative.nativeGetSegmentedInput(handle)

    /** @return true 表示插入锚点成功（合成串已更新并重解码） */
    fun insertManualSeparator(): Boolean = GkNative.nativeInsertManualSeparator(handle) == 1

    /** 设置上文（v1.3 上下文通道）：光标前已上屏文本（UTF-8） */
    fun setContext(before: String?) = GkNative.nativeSetContext(handle, before)
    fun candidateCount(): Int = GkNative.nativeCandidateCount(handle)
    fun candidate(index: Int): String = GkNative.nativeGetCandidate(handle, index)
    fun candidatePinyin(index: Int): String = GkNative.nativeGetCandidatePinyin(handle, index)

    /** @return 上屏文本；null 表示提交失败 */
    fun commit(index: Int): String? = commitDetail(index)?.first

    /** @return (上屏文本, 拼音)；拼音用于用户词回写（§4.5） */
    fun commitDetail(index: Int): Pair<String, String>? =
        GkNative.nativeCommit(handle, index)?.let { arr ->
            arr[0] to arr.getOrElse(1) { "" }
        }

    fun t9PinyinCount(): Int = GkNative.nativeT9PinyinCount(handle)
    fun t9Pinyin(index: Int): String = GkNative.nativeGetT9Pinyin(handle, index)
    fun learn(word: String, pinyin: String, source: Int) =
        GkNative.nativeLearn(handle, word, pinyin, source)

    fun loadUserWords(blob: ByteArray) = GkNative.nativeLoadUserWords(handle, blob)
    fun sessionEnd() = GkNative.nativeSessionEnd(handle)
}
