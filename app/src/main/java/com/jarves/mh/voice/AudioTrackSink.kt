package com.jarves.mh.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

interface PcmAudioSink {
    fun start()
    fun write(samples: ShortArray)
    fun stop()
    fun release()
}

/**
 * Streams synthesized PCM to the speaker in bounded chunks. The sink never
 * requires a complete response buffer and is safe to stop for barge-in.
 */
class AudioTrackSink(
    private val format: PcmAudioFormat = PcmAudioFormat(),
) : PcmAudioSink {
    private val started = AtomicBoolean(false)
    private var track: AudioTrack? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioTrack does not support ${format.sampleRateHz} Hz mono PCM" }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, format.sampleRateHz * 2 / 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    override fun write(samples: ShortArray) {
        if (samples.isEmpty()) return
        if (!started.get()) start()
        var offset = 0
        while (offset < samples.size && started.get()) {
            val written = track?.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING) ?: 0
            if (written <= 0) break
            offset += written
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
        }
    }

    override fun release() {
        stop()
        track?.release()
        track = null
    }
}
