package com.jarves.mh.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

interface TtsEngine {
    /** Speak text; onDone fires once after the LAST chunk finishes (not fired if stopped). */
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    val isSpeaking: Boolean
    fun release()
}

/** M1 engine: Android TextToSpeech. M2 can add PiperTtsEngine (ONNX) behind the same interface. */
class AndroidTtsEngine(context: Context, private val locale: Locale = Locale.getDefault()) : TtsEngine {
    private var ready = false
    private val pending = ArrayDeque<Pair<String, () -> Unit>>()
    @Volatile private var speaking = false
    private var generation = 0   // bumps on stop(), so stale onDone callbacks are ignored

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = locale
            while (pending.isNotEmpty()) pending.removeFirst().let { speak(it.first, it.second) }
        }
    }

    override val isSpeaking: Boolean get() = speaking

    override fun speak(text: String, onDone: () -> Unit) {
        if (!ready) { pending.addLast(text to onDone); return }
        val chunks = SpeechShaper.chunks(text)
        if (chunks.isEmpty()) { onDone(); return }
        val gen = generation
        speaking = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { finish(id) }
            override fun onDone(id: String?) { finish(id) }
            private fun finish(id: String?) {
                if (gen != generation) return
                if (id == "last") { speaking = false; onDone() }
            }
        })
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) "last" else "c$i"
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    override fun stop() { generation++; speaking = false; tts.stop() }
    override fun release() { generation++; tts.stop(); tts.shutdown() }
}
