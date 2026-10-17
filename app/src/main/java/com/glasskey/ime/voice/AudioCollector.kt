package com.glasskey.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 音频采集 + 能量 VAD（§5.6）：
 * 16kHz / 16bit / mono PCM，仅内存流转，不落盘不联网（§S-05，代码审计项）。
 * VAD：帧能量（RMS）双阈值滞回 —— 超过 [start] 判语音、低于 [stop] 计静音，
 * 静音 800ms 自动停（§5.6 交互规格）。
 */
class AudioCollector(private val listener: Listener) {

    /** v1.5.4：静音自动停开关（用户要求改为持续识别直到手动停，默认关闭） */
    @Volatile var silenceAutoStop: Boolean = false

    interface Listener {
        fun onLevel(rms: Float)                 // 电平（0..1，UI 动画）
        fun onPartialText(text: String)         // 流式中间结果（灰显预览）
        fun onSilenceTimeout()                  // 静音自动停
        fun onError(message: String)

        /** 识别引擎音频接入点（v1.2）：每 20ms 帧回调（录音线程串行），返回流式 partial */
        fun onAudioFrame(pcm: ShortArray, len: Int): String = ""
    }

    @Volatile
    var isRecording = false
        private set

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var speechSeen = false
    private var silentFrames = 0

    private val silenceAutoStopFrames = (SILENCE_TIMEOUT_MS / FRAME_MS).toInt()

    @SuppressLint("MissingPermission")  // 调用方已检查 RECORD_AUDIO
    fun start(): Boolean {
        if (isRecording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            listener.onError("音频初始化失败")
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SAMPLES * 4)
            )
        } catch (t: Throwable) {
            listener.onError("无法访问麦克风：${t.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            listener.onError("麦克风不可用")
            return false
        }
        recorder = rec
        speechSeen = false
        silentFrames = 0
        rec.startRecording()
        isRecording = true
        thread = Thread({
            val buf = ShortArray(FRAME_SAMPLES)
            while (isRecording) {
                val n = rec.read(buf, 0, FRAME_SAMPLES)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                val rms = kotlin.math.sqrt(sum / n).toFloat()
                listener.onLevel((rms / 3000f).coerceIn(0f, 1f))
                // 能量 VAD（§5.6 静音 800ms 自动停）
                if (rms > VAD_START_RMS) {
                    speechSeen = true
                    silentFrames = 0
                } else if (silenceAutoStop && speechSeen && rms < VAD_STOP_RMS) {
                    silentFrames++
                    if (silentFrames >= silenceAutoStopFrames) {
                        listener.onSilenceTimeout()
                        break
                    }
                }
                // 识别引擎接入点（v1.2 已落地）：pcm 块送 sherpa-onnx 流式会话 feedAudio，
                // 返回的流式 partial 回调 UI 灰显预览（§5.6）
                val partial = listener.onAudioFrame(buf, n)
                if (partial.isNotEmpty()) listener.onPartialText(partial)
            }
        }, "gk-audio").also { it.start() }
        return true
    }

    fun stop() {
        isRecording = false
        try {
            thread?.join(300)
        } catch (_: InterruptedException) {
        }
        thread = null
        recorder?.run {
            try {
                stop()
            } catch (_: Throwable) {
            }
            release()
        }
        recorder = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_MS = 20
        val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS  // 320 samples
        const val SILENCE_TIMEOUT_MS = 800L
        const val VAD_START_RMS = 1200f
        const val VAD_STOP_RMS = 600f
    }
}
