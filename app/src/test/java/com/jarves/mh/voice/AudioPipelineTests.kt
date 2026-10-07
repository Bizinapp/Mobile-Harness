package com.jarves.mh.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTests {
    @Test
    fun splitterEmitsTwentyMillisecondMonoFrames() {
        val splitter = PcmFrameSplitter(PcmAudioFormat(16_000, 1), 20)
        assertTrue(splitter.push(ShortArray(159)).isEmpty())
        val frames = splitter.push(ShortArray(161))
        assertEquals(1, frames.size)
        assertEquals(320, frames.single().samples.size)
        assertEquals(20, frames.single().durationMillis)
    }

    @Test
    fun splitterRetainsRemainderAcrossReads() {
        val splitter = PcmFrameSplitter(frameDurationMillis = 20)
        assertEquals(2, splitter.push(ShortArray(650)).size)
        assertEquals(10, splitter.flush()?.samples?.size)
    }

    @Test
    fun energyVadRejectsSilenceAndAcceptsSpeechLikeSamples() {
        val vad = EnergyVadEngine()
        val silent = PcmFrame(ShortArray(320))
        val speech = PcmFrame(ShortArray(320) { if (it % 2 == 0) 9_000 else -9_000 })
        assertFalse(vad.isSpeech(silent))
        assertTrue(vad.isSpeech(speech))
    }
}
