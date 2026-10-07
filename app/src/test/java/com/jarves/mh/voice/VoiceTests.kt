package com.jarves.mh.voice

import org.junit.Assert.*
import org.junit.Test

class FakeStt : SttEngine {
    var listener: SttEngine.Listener? = null
    override fun start(listener: SttEngine.Listener) { this.listener = listener }
    override fun stop() { listener?.onFinal("") }
    override fun cancel() { listener = null }
    override fun release() {}
    fun say(text: String) = listener!!.onFinal(text)
}
class FakeTts : TtsEngine {
    val spoken = mutableListOf<String>()
    private var done: (() -> Unit)? = null
    override var isSpeaking = false
    override fun speak(text: String, onDone: () -> Unit) { spoken += text; done = onDone; isSpeaking = true }
    override fun stop() { done = null; isSpeaking = false }
    override fun release() {}
    fun finish() { isSpeaking = false; done?.invoke() }
}
class FakeBackend : VoiceBackend {
    val prompts = mutableListOf<String>(); var stops = 0; val approvals = mutableListOf<Boolean>()
    override fun sendPrompt(text: String) { prompts += text }
    override fun stopTask() { stops++ }
    override fun answerApproval(approved: Boolean) { approvals += approved }
}

class VoiceTests {
    private fun rig() = Triple(FakeStt(), FakeTts(), FakeBackend()).let { (s, t, b) -> listOf(s, t, b, VoiceController(s, t, b)) }

    @Test fun shaperStripsMarkdownAndLimitsSentences() {
        val out = SpeechShaper.speakable("**Done.** See `x`. One. Two. Three. Four.")
        assertFalse(out.contains("*")); assertFalse(out.contains("`"))
        assertTrue(out.split(Regex("(?<=[.!?])\\s+")).size <= 3)
        assertTrue(SpeechShaper.speakable("```\ncode\n```").contains("shown on screen"))
    }

    @Test fun yesNoDefaultsToSafe() {
        assertEquals(YesNo.Answer.YES, YesNo.parse("Yes, go ahead"))
        assertEquals(YesNo.Answer.NO, YesNo.parse("no, do it later"))   // NO wins over a later "do it"
        assertEquals(YesNo.Answer.NO, YesNo.parse("stop"))
        assertEquals(YesNo.Answer.UNCLEAR, YesNo.parse("hmm maybe"))
    }

    @Test fun fullTurnListenSendSpeak() {
        val (stt, tts, be, c) = rig().let { arrayOf(it[0] as FakeStt, it[1] as FakeTts, it[2] as FakeBackend, it[3] as VoiceController) }
        c.onMicPressed(); assertEquals(VoicePhase.LISTENING, c.phase.value)
        stt.say("open settings"); assertEquals(listOf("open settings"), be.prompts)
        assertEquals(VoicePhase.THINKING, c.phase.value)
        c.onAgentSnapshot(AgentSnapshot(true, "m1", "old", null))
        c.onAgentSnapshot(AgentSnapshot(false, "m2", "Battery is 80 percent.", null))
        assertEquals(VoicePhase.SPEAKING, c.phase.value); assertTrue(tts.spoken.last().contains("Battery"))
        tts.finish(); assertEquals(VoicePhase.IDLE, c.phase.value)
    }

    @Test fun bargeInStopsSpeechAndListens() {
        val (stt, tts, _, c) = rig().let { arrayOf(it[0] as FakeStt, it[1] as FakeTts, it[2] as FakeBackend, it[3] as VoiceController) }
        c.onAgentSnapshot(AgentSnapshot(true, "a", "x", null)); c.onAgentSnapshot(AgentSnapshot(false, "b", "Long answer.", null))
        assertEquals(VoicePhase.SPEAKING, c.phase.value)
        c.onMicPressed()
        assertFalse(tts.isSpeaking); assertEquals(VoicePhase.LISTENING, c.phase.value)
    }

    @Test fun approvalYesNoAndUnclearIsNo() {
        val parts = rig(); val stt = parts[0] as FakeStt; val tts = parts[1] as FakeTts
        val be = parts[2] as FakeBackend; val c = parts[3] as VoiceController
        c.onAgentSnapshot(AgentSnapshot(true, null, null, "Okay to run npm install?"))
        assertEquals(VoicePhase.AWAITING_APPROVAL, c.phase.value); tts.finish()   // question spoken -> listens
        stt.say("yes"); assertEquals(listOf(true), be.approvals)
        // second approval: unclear twice -> denied
        c.onAgentSnapshot(AgentSnapshot(true, null, null, null))
        c.onAgentSnapshot(AgentSnapshot(true, null, null, "Okay to delete files?")); tts.finish()
        stt.say("hmm"); tts.finish(); stt.say("uh"); assertEquals(listOf(true, false), be.approvals)
    }

    @Test fun spokenStopCancelsRunningTask() {
        val parts = rig(); val stt = parts[0] as FakeStt; val be = parts[2] as FakeBackend; val c = parts[3] as VoiceController
        c.onMicPressed(); stt.say("build the app"); c.onAgentSnapshot(AgentSnapshot(true, null, null, null))
        c.onMicPressed(); // while THINKING -> stops task, listens
        assertEquals(1, be.stops)
    }
}

class VoiceLearningTests {
    private class B : VoiceBackend {
        val prompts = mutableListOf<String>(); val corrections = mutableListOf<String>(); var hasTask = false
        override fun sendPrompt(text: String) { prompts += text }
        override fun stopTask() {}
        override fun answerApproval(approved: Boolean) {}
        override fun hasLastTask() = hasTask
        override fun correct(text: String) { corrections += text }
    }
    @Test fun sensitiveRequestWaitsForYes() {
        val stt = FakeStt(); val tts = FakeTts(); val b = B(); val c = VoiceController(stt, tts, b)
        c.onMicPressed(); stt.say("pay my rent in the bank app")
        assertTrue(b.prompts.isEmpty()); assertEquals(VoicePhase.AWAITING_APPROVAL, c.phase.value)
        tts.finish(); stt.say("yes"); assertEquals(listOf("pay my rent in the bank app"), b.prompts)
    }
    @Test fun sensitiveRequestDeniedOnNoOrUnclear() {
        val stt = FakeStt(); val tts = FakeTts(); val b = B(); val c = VoiceController(stt, tts, b)
        c.onMicPressed(); stt.say("delete all my photos"); tts.finish(); stt.say("no")
        assertTrue(b.prompts.isEmpty()); assertEquals(VoicePhase.IDLE, c.phase.value)
    }
    @Test fun correctionGoesToLearningNotToTheAgent() {
        val stt = FakeStt(); val tts = FakeTts(); val b = B().apply { hasTask = true }; val c = VoiceController(stt, tts, b)
        c.onMicPressed(); stt.say("No, always use oat milk")
        assertEquals(listOf("No, always use oat milk"), b.corrections); assertTrue(b.prompts.isEmpty())
    }
    @Test fun noCorrectionWithoutAPreviousTask() {
        val stt = FakeStt(); val tts = FakeTts(); val b = B(); val c = VoiceController(stt, tts, b)
        c.onMicPressed(); stt.say("No, I meant open settings")
        assertEquals(1, b.prompts.size)
    }
}
