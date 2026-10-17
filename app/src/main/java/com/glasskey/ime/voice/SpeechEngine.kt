package com.glasskey.ime.voice

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 语音识别引擎管理（§5.6 / §5.11 / §7.4）：
 * 本地优先 —— 完全离线推理，音频仅内存流转（§S-05）。
 *
 * v1.2：sherpa-onnx 流式 Zipformer 中文 INT8 模型随 APK assets 内置分发
 * （assets/models/asr-zipformer-zh-int8/，约 26MB），首次会话直接从
 * AssetManager 加载推理器（sherpa-onnx v1.13.7 OnlineRecognizer 仅提供
 * AssetManager 构造器），语音输入功能真正可用，不再需要外部资源包导入。
 * [PlaceholderAsrEngine] 保留：模型缺失（异常裁剪包）时 UI 引导而非伪装结果。
 */
object VoiceModels {

    /** 模型目录 id（对齐 §7.4 资源包清单 id） */
    const val ASR_ZH_DIR = "asr-zipformer-zh-int8"

    /** APK 内置 assets 根路径（sherpa-onnx AssetManager 相对路径） */
    const val ASR_ZH_ASSET = "models/$ASR_ZH_DIR"

    fun modelsRoot(ctx: Context): File = File(ctx.filesDir, "models")

    fun asrDir(ctx: Context): File = File(modelsRoot(ctx), ASR_ZH_DIR)

    /** 模型文件名（§7.4 清单 v1.0.0；SHA-256 校验见 model_manifest.json） */
    val ASR_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.int8.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt",
        "silero_vad.onnx",
    )

    /** 模型是否就绪：APK assets 内置（v1.2 主路径）或 filesDir 手动导入（兼容旧引导） */
    fun asrReady(ctx: Context): Boolean {
        // assets 内置：逐一确认模型文件存在
        val assetOk = try {
            val names = ctx.assets.list(ASR_ZH_ASSET)?.map { it.lowercase() }.orEmpty()
            names.any { it.startsWith("encoder") } &&
                names.any { it.startsWith("decoder") } &&
                names.any { it.startsWith("joiner") } &&
                names.any { it.contains("tokens") }
        } catch (_: Throwable) {
            false
        }
        if (assetOk) return true
        // filesDir 手动导入资源包（旧路径兼容）
        val dir = asrDir(ctx)
        if (!dir.isDirectory) return false
        val names = dir.listFiles()?.map { it.name.lowercase() }.orEmpty()
        return names.any { it.startsWith("encoder") } &&
            names.any { it.startsWith("decoder") } &&
            names.any { it.startsWith("joiner") } &&
            names.any { it.contains("tokens") }
    }

    /** 资源包描述（导入页展示体积/来源/校验，§5.6 引导页要求） */
    fun describePackage(): String =
        "中文流式语音识别模型 asr-zipformer-zh-int8 v1.0.0 · 约 26MB（SHA-256 校验清单见 model_manifest.json）· " +
            "v1.2 起随 APK assets 内置，无需手动导入"
}

/** ASR 推理接口（§8.2 AIDL 的进程内简化；:voice 独立进程随后续版本启用） */
interface AsrEngine {
    fun startSession()

    /** 送入 16k/16bit/mono PCM 块；返回流式中间结果 */
    fun feedAudio(pcm: ShortArray, len: Int): String

    /** 结束会话并定稿文本 */
    fun stopSession(): String

    /** 会话级释放（引擎可复用，下次 startSession 重新开会话） */
    fun release()

    /** 面板销毁时的完整释放（默认空实现） */
    fun destroy() {}
}

/**
 * 占位识别器：模型资源包缺失时的兜底实现。
 * 不做任何真实转写（保持"零网络、零伪装"——不把噪声伪装成识别结果），
 * 返回空串，由 UI 提示模型导入引导。
 */
class PlaceholderAsrEngine : AsrEngine {
    override fun startSession() {}
    override fun feedAudio(pcm: ShortArray, len: Int): String = ""
    override fun stopSession(): String = ""
    override fun release() {}
}

/**
 * sherpa-onnx 真实识别器（v1.2，§5.6 本地语音输入落地）：
 * - 模型：sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23 INT8（assets 内置）；
 * - 音频：16kHz/16bit/mono，[feedAudio] 每 20ms 帧转为 float 送入流，
 *   isReady 驱动增量解码，返回流式 partial（灰显预览）；
 * - [stopSession] 调 inputFinished() 冲刷尾部特征后取最终结果；
 * - 端点检测由面板侧能量 VAD 负责（静音 800ms 自动停），引擎内不重复启用；
 * - 线程模型：录音线程串行调用 startSession/feedAudio/stopSession，内部互斥锁防护。
 */
class SherpaOnnxAsrEngine(assetManager: AssetManager) : AsrEngine {

    private val recognizer: OnlineRecognizer
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private val lock = Any()

    init {
        val dir = VoiceModels.ASR_ZH_ASSET
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioCollector.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$dir/decoder-epoch-99-avg-1.int8.onnx",
                    joiner = "$dir/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
                debug = false,
            ),
            enableEndpoint = false,  // 端点由能量 VAD（AudioCollector）驱动
        )
        recognizer = OnlineRecognizer(assetManager, config)
    }

    override fun startSession() {
        synchronized(lock) {
            releaseStreamLocked()
            stream = recognizer.createStream()
        }
    }

    override fun feedAudio(pcm: ShortArray, len: Int): String {
        val s = stream ?: return ""
        synchronized(lock) {
            val floats = FloatArray(len)
            for (i in 0 until len) floats[i] = pcm[i] / 32768.0f
            s.acceptWaveform(floats, AudioCollector.SAMPLE_RATE)
            while (recognizer.isReady(s)) recognizer.decode(s)
            return recognizer.getResult(s).text
        }
    }

    override fun stopSession(): String {
        val s = stream ?: return ""
        synchronized(lock) {
            s.inputFinished()
            while (recognizer.isReady(s)) recognizer.decode(s)
            val text = recognizer.getResult(s).text
            releaseStreamLocked()
            return text
        }
    }

    override fun release() {
        synchronized(lock) { releaseStreamLocked() }
    }

    override fun destroy() {
        synchronized(lock) {
            releaseStreamLocked()
            recognizer.release()
        }
    }

    private fun releaseStreamLocked() {
        stream?.release()
        stream = null
    }
}

/** 引擎工厂：按模型就绪状态选择真实/占位识别器（集中判定，便于冒烟注入） */
object AsrEngineFactory {
    fun create(ctx: Context): AsrEngine {
        return if (VoiceModels.asrReady(ctx)) {
            try {
                SherpaOnnxAsrEngine(ctx.assets)
            } catch (_: Throwable) {
                PlaceholderAsrEngine()  // 模型加载失败（异常包体）→ 兜底占位
            }
        } else {
            PlaceholderAsrEngine()
        }
    }
}

/**
 * v1.5.5 ASR 预热缓存：输入法服务启动即后台加载识别器（26MB INT8 模型），
 * 用户首次进语音面板/长按空格时直接取用 —— 冷启动等待从"秒级"降到接近 0。
 *
 * v1.6.3 常驻时长管理（用户需求：模型是否卸载只看「多久没用语音」，
 * 时长由用户在设置中自定义，不做硬编码内存判断）：
 *  - [hold]：面板用完归还引擎（不再立即 destroy），按设置安排空闲卸载；
 *  - [take]：取用时若已闲置超时 → 释放旧引擎并返回 null（面板重新加载）；
 *  - 设置值：0=每次用完即释放 / N 分钟=闲置 N 分钟卸载 / -1=常驻。
 *  实测内存：26MB INT8 模型运行占用约 30-50MB（java 堆 + native），
 *  默认 30 分钟常驻（GkPrefs.voiceKeepMinutes）。
 */
object AsrCache {
    private const val TAG = "GkAsrCache"

    @Volatile private var cached: AsrEngine? = null
    private var lastUsedAt: Long = 0L
    private val lock = Any()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var unloadRunnable: Runnable? = null

    fun prewarm(ctx: android.content.Context) {
        if (cached != null) return
        Thread {
            try {
                if (VoiceModels.asrReady(ctx)) {
                    val t0 = System.currentTimeMillis()
                    val e = AsrEngineFactory.create(ctx)
                    synchronized(lock) {
                        if (cached == null) cached = e else e.destroy()
                    }
                    android.util.Log.i(TAG, "prewarm done in ${System.currentTimeMillis() - t0}ms")
                }
            } catch (_: Throwable) { }
        }.start()
    }

    /** 取走缓存的识别器（面板接管生命周期） */
    fun take(): AsrEngine? {
        cancelUnload()
        val e = synchronized(lock) {
            val c = cached
            cached = null
            c
        } ?: return null
        lastUsedAt = System.currentTimeMillis()
        return e
    }

    /**
     * 面板用完归还引擎（v1.6.3）：
     *  - keep=0（每次释放）→ 立即 destroy；
     *  - keep>0 → 缓存并安排 N 分钟后无使用则卸载；
     *  - keep<0（常驻）→ 缓存不卸载。
     */
    fun hold(engine: AsrEngine?, ctx: android.content.Context?) {
        val keepMin = ctx?.let { com.glasskey.app.GkPrefs.voiceKeepMinutes(it) } ?: 30
        if (engine == null) return
        if (keepMin == 0) {
            try { engine.destroy() } catch (_: Throwable) { }
            return
        }
        synchronized(lock) {
            // 已有缓存（预热竞态）→ 保留先到的，销毁后归还的
            cached?.let { old -> if (old !== engine) try { old.destroy() } catch (_: Throwable) {} }
            cached = engine
            lastUsedAt = System.currentTimeMillis()
        }
        scheduleUnload(keepMin)
    }

    private fun scheduleUnload(keepMin: Int) {
        if (keepMin < 0) return  // 常驻
        cancelUnload()
        val r = Runnable {
            val idleMs = System.currentTimeMillis() - lastUsedAt
            if (idleMs >= keepMin * 60_000L) {
                synchronized(lock) {
                    cached?.let { try { it.destroy() } catch (_: Throwable) {} }
                    cached = null
                }
                android.util.Log.i(TAG, "idle ${idleMs / 1000}s > ${keepMin}min → asr unloaded")
            } else {
                scheduleUnload(keepMin)  // 期间被用过，重新计时
            }
        }
        unloadRunnable = r
        handler.postDelayed(r, keepMin * 60_000L)
    }

    private fun cancelUnload() {
        unloadRunnable?.let { handler.removeCallbacks(it) }
        unloadRunnable = null
    }
}
