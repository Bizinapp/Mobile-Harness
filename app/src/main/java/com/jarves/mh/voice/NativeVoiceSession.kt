package com.jarves.mh.voice

import java.util.concurrent.atomic.AtomicBoolean

interface GatedSttEngine : SttEngine {
    fun accept(frame: PcmFrame)
}

interface StreamingNeuralTts : TtsEngine {
    /** Provider emits PCM chunks no larger than the requested duration. */
    fun speakStreaming(text: String, maxChunkMillis: Int = 250, onDone: () -> Unit = {})
}

/**
 * Native audio data plane:
 * AudioRecord -> fixed PCM frames -> VAD -> local STT.
 *
 * The model implementations are injected. This keeps the voice controller
 * independent from ONNX Runtime/whisper.cpp packaging and lets the app fall
 * back to Android speech services when local models are unavailable.
 */
class NativeVoiceSession(
    private val source: PcmAudioSource,
    private val vad: VadEngine,
    private val stt: GatedSttEngine,
) {
    private val running = AtomicBoolean(false)
    private var listener: SttEngine.Listener? = null

    fun start(listener: SttEngine.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        vad.reset()
        stt.start(listener)
        source.start(object : PcmAudioSource.Listener {
            override fun onFrame(frame: PcmFrame) {
                if (!running.get()) return
                if (vad.isSpeech(frame)) stt.accept(frame)
            }

            override fun onError(message: String) {
                if (running.get()) listener.onError(message)
            }
        })
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        source.stop()
        stt.stop()
        listener = null
    }

    fun cancel() {
        if (!running.compareAndSet(true, false)) return
        source.stop()
        stt.cancel()
        listener = null
    }

    fun release() {
        cancel()
        source.release()
        vad.close()
        stt.release()
    }
}

/** Production model boundary: Silero VAD INT8 ONNX. */
interface SileroVadModel : SileroVadProvider

/** Production model boundary: whisper.cpp tiny.en Q5_K_M via JNI. */
interface WhisperSttModel : GatedSttEngine {
    val modelId: String
}

/** Production model boundary: KittenTTS or Piper PCM synthesis. */
interface NeuralTtsModel : StreamingNeuralTts {
    val modelId: String
}
