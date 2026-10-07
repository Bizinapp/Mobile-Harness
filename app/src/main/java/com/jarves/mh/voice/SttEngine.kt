package com.jarves.mh.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

interface SttEngine {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }
    fun start(listener: Listener)
    fun stop()          // finish and deliver what was heard
    fun cancel()        // drop everything
    fun release()
}

/**
 * M1 engine: Android's SpeechRecognizer (no NDK needed). Prefers the on-device recognizer on API 33+.
 * M1.5 swaps in WhisperSttEngine (whisper.cpp via JNI) behind the same interface.
 * Must be created and used on the main thread.
 */
class AndroidSttEngine(private val context: Context, private val locale: Locale = Locale.getDefault()) : SttEngine {
    private var recognizer: SpeechRecognizer? = null

    private fun ensure(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = when {
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
        recognizer = r
        return r
    }

    override fun start(listener: SttEngine.Listener) {
        val r = ensure() ?: return listener.onError("No speech recognizer on this device")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { first(b)?.let(listener::onPartial) }
            override fun onResults(b: Bundle?) { listener.onFinal(first(b).orEmpty()) }
            override fun onError(code: Int) {
                // NO_MATCH(7) / SPEECH_TIMEOUT(6) are normal "heard nothing" cases, not failures.
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onFinal("")
                else listener.onError("Speech recognition error $code")
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel() }
    override fun release() { recognizer?.destroy(); recognizer = null }
}
