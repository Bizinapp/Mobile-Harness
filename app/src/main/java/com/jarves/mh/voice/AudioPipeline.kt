package com.jarves.mh.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Fixed audio contract shared by VAD, STT and playback providers. */
data class PcmAudioFormat(
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channels == 1) { "The voice pipeline currently requires mono PCM" }
    }
}

data class PcmFrame(
    val samples: ShortArray,
    val format: PcmAudioFormat = PcmAudioFormat(),
    val timestampNanos: Long = System.nanoTime(),
) {
    val durationMillis: Int get() = samples.size * 1_000 / format.sampleRateHz
}

/** Deterministic frame splitter; does not retain references to caller-owned arrays. */
class PcmFrameSplitter(
    private val format: PcmAudioFormat = PcmAudioFormat(),
    frameDurationMillis: Int = 20,
) {
    private val frameSamples = format.sampleRateHz * frameDurationMillis / 1_000
    private var pending = ShortArray(0)

    init {
        require(frameDurationMillis in 10..100) { "frameDurationMillis must be between 10 and 100" }
        require(frameSamples > 0) { "frameDurationMillis is too small for the sample rate" }
    }

    fun push(samples: ShortArray, timestampNanos: Long = System.nanoTime()): List<PcmFrame> {
        if (samples.isEmpty()) return emptyList()
        val merged = ShortArray(pending.size + samples.size)
        pending.copyInto(merged)
        samples.copyInto(merged, pending.size)
        val result = ArrayList<PcmFrame>(merged.size / frameSamples)
        var offset = 0
        while (merged.size - offset >= frameSamples) {
            result += PcmFrame(
                samples = merged.copyOfRange(offset, offset + frameSamples),
                format = format,
                timestampNanos = timestampNanos,
            )
            offset += frameSamples
        }
        pending = merged.copyOfRange(offset, merged.size)
        return result
    }

    fun flush(): PcmFrame? = pending.takeIf { it.isNotEmpty() }?.let {
        val frame = PcmFrame(it.copyOf(), format)
        pending = ShortArray(0)
        frame
    }
}

interface VadEngine {
    fun reset()
    fun isSpeech(frame: PcmFrame): Boolean
    fun close() {}
}

/** Safe fallback used until the Silero ONNX provider is installed. */
class EnergyVadEngine(
    private val threshold: Double = 0.012,
) : VadEngine {
    override fun reset() = Unit

    override fun isSpeech(frame: PcmFrame): Boolean {
        if (frame.samples.isEmpty()) return false
        var sum = 0.0
        var squared = 0.0
        frame.samples.forEach { sample ->
            val normalized = sample / 32768.0
            sum += abs(normalized)
            squared += normalized * normalized
        }
        val rms = sqrt(squared / frame.samples.size)
        return rms >= threshold || sum / frame.samples.size >= threshold * 0.72
    }
}

/**
 * Boundary for the production Silero VAD INT8 ONNX implementation.
 * The model runner is deliberately injected so the base APK does not acquire a
 * second copy of ONNX Runtime when a device has no local-model voice profile.
 */
interface SileroVadProvider : VadEngine {
    val modelId: String
}
