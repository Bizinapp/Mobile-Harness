package com.jarves.mh.voice

import androidx.lifecycle.viewModelScope
import com.jarves.mh.learning.LearningSidecar
import com.jarves.mh.ui.AppUiState
import com.jarves.mh.ui.MainViewModel
import kotlinx.coroutines.launch

/** Plain backend: no learning. */
fun MainViewModel.asVoiceBackend(): VoiceBackend = object : VoiceBackend {
    override fun sendPrompt(text: String) = this@asVoiceBackend.sendPrompt(text)
    override fun stopTask() = this@asVoiceBackend.stopTask()
    override fun answerApproval(approved: Boolean) = this@asVoiceBackend.answerApproval(approved)
}

/**
 * Backend with the learning loop: prepare (memory + skill hint staged for the Claude bridge) -> run -> finish (learn).
 * `sidecar` may be null (Python side not installed): everything then behaves like the plain backend.
 */
fun MainViewModel.asLearningVoiceBackend(sidecar: LearningSidecar?): VoiceBackend = object : VoiceBackend {
    private var utterance: String? = null
    private var skill: String? = null
    private var running = false
    private var lastTask = false

    override fun sendPrompt(text: String) {
        utterance = text; skill = null
        viewModelScope.launch {
            sidecar?.prepare(text)?.let { skill = it.skill }   // stages the addendum file the bridge reads
            this@asLearningVoiceBackend.sendPrompt(text)
        }
    }
    override fun stopTask() = this@asLearningVoiceBackend.stopTask()
    override fun answerApproval(approved: Boolean) = this@asLearningVoiceBackend.answerApproval(approved)

    override fun onSnapshot(s: AgentSnapshot) {
        if (s.isRunning) { running = true; return }
        if (!running) return
        running = false
        val u = utterance ?: return
        val reply = s.lastAssistantText.orEmpty()
        val ok = reply.isNotBlank() && !reply.startsWith("Error", ignoreCase = true)
        lastTask = true
        viewModelScope.launch { sidecar?.finish(u, reply, s.lastSteps, ok, s.lastWorkedMillis / 1000.0, skill) }
    }
    override fun hasLastTask() = lastTask && sidecar != null
    override fun correct(text: String) { viewModelScope.launch { sidecar?.correct(text) } }
}

fun AppUiState.toAgentSnapshot(): AgentSnapshot {
    val last = messages.lastOrNull { !it.fromUser }
    val req = pendingApproval
    return AgentSnapshot(
        isRunning = isRunning,
        lastAssistantId = last?.id,
        lastAssistantText = last?.text,
        approvalQuestion = req?.let { "Okay to ${it.explanation.ifBlank { it.toolName }}?" },
        lastSteps = last?.workItems.orEmpty().map { it.title to it.detail },
        lastWorkedMillis = last?.workedMillis ?: 0L,
    )
}
