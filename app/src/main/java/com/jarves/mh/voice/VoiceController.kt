package com.jarves.mh.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING, AWAITING_APPROVAL }

/** What the voice layer needs from the agent app. Implemented by VoiceBinding on top of MainViewModel. */
interface VoiceBackend {
    fun sendPrompt(text: String)
    fun stopTask()
    fun answerApproval(approved: Boolean)
    /** Learning hooks (defaults keep plain backends working). */
    fun onSnapshot(s: AgentSnapshot) {}
    fun hasLastTask(): Boolean = false
    fun correct(text: String) {}
}

/** A tiny view of agent state; the controller never sees the whole AppUiState. */
data class AgentSnapshot(
    val isRunning: Boolean,
    val lastAssistantId: String?,
    val lastAssistantText: String?,
    val approvalQuestion: String?,   // non-null while the agent waits for a yes/no
    val lastSteps: List<Pair<String, String>> = emptyList(),   // (title, detail) of the last reply's work items
    val lastWorkedMillis: Long = 0L,
)

/**
 * The push-to-talk conversation loop:
 *   mic -> LISTENING -> THINKING (agent runs) -> SPEAKING reply -> IDLE
 * Approvals: the agent asks, we speak the question, listen, and map yes/no (unclear = no).
 * Barge-in: pressing the mic while SPEAKING/THINKING stops speech (and the task) and listens again.
 * All methods must be called on the main thread; `post` hops back to it from engine callbacks.
 */
class VoiceController(
    private val stt: SttEngine,
    private val tts: TtsEngine,
    private val backend: VoiceBackend,
    private val post: (() -> Unit) -> Unit = { it() },
) {
    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    private val _partial = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    val phase: StateFlow<VoicePhase> = _phase
    val partial: StateFlow<String> = _partial
    val error: StateFlow<String?> = _error
    var speakReplies = true

    private var wasRunning = false
    private var lastSpokenId: String? = null
    private var approvalOpen = false
    private var approvalRetries = 0
    private var taskInFlight = false
    private var awaitReply = false
    private var preApproval: String? = null   // a sensitive request waiting for the user's yes/no BEFORE it is sent

    // ---- user actions ------------------------------------------------------
    fun onMicPressed() {
        _error.value = null
        when (_phase.value) {
            VoicePhase.IDLE -> listen()
            VoicePhase.LISTENING -> stt.stop()                      // finish and deliver
            VoicePhase.SPEAKING -> { tts.stop(); listen() }        // barge-in
            VoicePhase.THINKING -> { backend.stopTask(); tts.stop(); listen() }
            VoicePhase.AWAITING_APPROVAL -> { tts.stop(); listen() }   // re-ask: answer is read by handleFinal
        }
    }

    fun cancelAll() {
        stt.cancel(); tts.stop()
        if (taskInFlight) backend.stopTask()
        _partial.value = ""
        _phase.value = VoicePhase.IDLE
    }

    // ---- agent -> voice ----------------------------------------------------
    fun onAgentSnapshot(s: AgentSnapshot) {
        backend.onSnapshot(s)
        // approval needs an answer: speak the question, then listen
        if (s.approvalQuestion != null && !approvalOpen) {
            approvalOpen = true; approvalRetries = 0
            askApproval(s.approvalQuestion)
            return
        }
        if (s.approvalQuestion == null) approvalOpen = false

        // task finished: running true -> false; then speak the NEW assistant message
        // (it can land in a later state update than the isRunning flip, so we wait for it)
        if (s.isRunning) awaitReply = false
        if (wasRunning && !s.isRunning) { taskInFlight = false; awaitReply = true }
        wasRunning = s.isRunning
        if (awaitReply && !s.isRunning) {
            val id = s.lastAssistantId
            val text = s.lastAssistantText
            if (text != null && id != null && id != lastSpokenId) {
                awaitReply = false
                lastSpokenId = id
                if (speakReplies) {
                    _phase.value = VoicePhase.SPEAKING
                    tts.speak(SpeechShaper.speakable(text)) { post { if (_phase.value == VoicePhase.SPEAKING) _phase.value = VoicePhase.IDLE } }
                } else if (_phase.value == VoicePhase.THINKING) _phase.value = VoicePhase.IDLE
            }
        }
    }

    // ---- internals ---------------------------------------------------------
    private fun listen() {
        _partial.value = ""
        _phase.value = if (approvalOpen) VoicePhase.AWAITING_APPROVAL else VoicePhase.LISTENING
        stt.start(object : SttEngine.Listener {
            override fun onPartial(text: String) = post { _partial.value = text }
            override fun onFinal(text: String) = post { handleFinal(text.trim()) }
            override fun onError(message: String) = post { _error.value = message; _phase.value = VoicePhase.IDLE }
        })
    }

    private fun handleFinal(text: String) {
        _partial.value = ""
        if (preApproval != null) { handlePreApproval(text); return }
        if (approvalOpen) { handleApprovalAnswer(text); return }
        if (text.isBlank()) { _phase.value = VoicePhase.IDLE; return }
        if (!taskInFlight && backend.hasLastTask() && Sensitive.CORRECTION.containsMatchIn(text)) {
            backend.correct(text); tts.speak("Got it. I'll do it that way from now on."); _phase.value = VoicePhase.IDLE; return
        }
        if (taskInFlight && CANCEL.matches(text)) { backend.stopTask(); _phase.value = VoicePhase.IDLE; return }
        if (Sensitive.REQUEST.containsMatchIn(text)) {
            preApproval = text
            _phase.value = VoicePhase.AWAITING_APPROVAL
            tts.speak("That looks sensitive. Okay to go ahead? Say yes or no.") { post { if (preApproval != null) listen() } }
            return
        }
        dispatch(text)
    }

    private fun dispatch(text: String) {
        taskInFlight = true
        _phase.value = VoicePhase.THINKING
        backend.sendPrompt(text)
        if (speakReplies) tts.speak("On it.")
    }

    private var preRetries = 0
    private fun handlePreApproval(text: String) {
        val pending = preApproval ?: return
        when (YesNo.parse(text)) {
            YesNo.Answer.YES -> { preApproval = null; preRetries = 0; dispatch(pending) }
            YesNo.Answer.NO -> { preApproval = null; preRetries = 0; tts.speak("Okay, I won't."); _phase.value = VoicePhase.IDLE }
            YesNo.Answer.UNCLEAR ->
                if (preRetries++ < 1) tts.speak("Please say yes or no.") { post { if (preApproval != null) listen() } }
                else { preApproval = null; preRetries = 0; tts.speak("Okay, I won't."); _phase.value = VoicePhase.IDLE }   // unclear = no
        }
    }

    private fun askApproval(question: String) {
        _phase.value = VoicePhase.AWAITING_APPROVAL
        tts.speak(SpeechShaper.speakable(question, maxSentences = 2)) { post { if (approvalOpen) listen() } }
    }

    private fun handleApprovalAnswer(text: String) {
        when (YesNo.parse(text)) {
            YesNo.Answer.YES -> finishApproval(true)
            YesNo.Answer.NO -> finishApproval(false)
            YesNo.Answer.UNCLEAR ->
                if (approvalRetries++ < 1) { tts.speak("Please say yes or no.") { post { if (approvalOpen) listen() } } }
                else finishApproval(false)      // still unclear: safe default is no
        }
    }

    private fun finishApproval(approved: Boolean) {
        approvalOpen = false
        _phase.value = VoicePhase.THINKING
        backend.answerApproval(approved)
        tts.speak(if (approved) "Okay." else "Okay, I won't.")
    }

    fun release() { stt.release(); tts.release() }

    companion object {
        private val CANCEL = Regex("^(stop|cancel|never ?mind|abort)( that| it| the task)?[.!]?$", RegexOption.IGNORE_CASE)
    }
}
