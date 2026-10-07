package com.jarves.mh.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface PcmAudioSource {
    fun start(listener: Listener)
    fun stop()
    fun release()

    interface Listener {
        fun onFrame(frame: PcmFrame)
        fun onError(message: String)
    }
}

/** Native microphone source for the 16 kHz mono voice pipeline. */
class AudioRecordSource(
    private val format: PcmAudioFormat = PcmAudioFormat(),
    private val frameDurationMillis: Int = 20,
) : PcmAudioSource {
    private val running = AtomicBoolean(false)
    private val recordRef = AtomicReference<AudioRecord?>(null)
    private var thread: Thread? = null

    override fun start(listener: PcmAudioSource.Listener) {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            running.set(false)
            listener.onError("AudioRecord does not support ${format.sampleRateHz} Hz mono PCM")
            return
        }
        val frameSamples = format.sampleRateHz * frameDurationMillis / 1_000
        val bufferBytes = maxOf(minBuffer, frameSamples * 2 * 4)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                format.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        }.getOrElse {
            running.set(false)
            listener.onError("Could not create AudioRecord: ${it.message ?: "unknown error"}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            listener.onError("AudioRecord failed to initialize")
            return
        }
        recordRef.set(record)
        thread = Thread({ captureLoop(record, listener, frameSamples) }, "mh-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun captureLoop(record: AudioRecord, listener: PcmAudioSource.Listener, frameSamples: Int) {
        val splitter = PcmFrameSplitter(format, frameDurationMillis)
        val buffer = ShortArray(frameSamples * 2)
        try {
            record.startRecording()
            while (running.get()) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    listener.onError("AudioRecord read failed: $count")
                    break
                }
                if (count > 0) splitter.push(buffer.copyOf(count)).forEach(listener::onFrame)
            }
        } catch (security: SecurityException) {
            listener.onError("Microphone permission is required")
        } catch (error: Throwable) {
            if (running.get()) listener.onError("Microphone capture failed: ${error.message ?: "unknown error"}")
        } finally {
            runCatching { record.stop() }
            record.release()
            recordRef.compareAndSet(record, null)
            running.set(false)
        }
    }

    override fun stop() {
        running.set(false)
        recordRef.getAndSet(null)?.let { runCatching { it.stop() } }
        thread?.interrupt()
        thread = null
    }

    override fun release() = stop()
}
