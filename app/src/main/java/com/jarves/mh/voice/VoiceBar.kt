package com.jarves.mh.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.ui.AppUiState
import com.jarves.mh.ui.MainViewModel

/**
 * Drop-in voice strip. Usage: VoiceBar(viewModel)  or  VoiceBar(state, backend).
 */
@Composable
fun VoiceBar(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VoiceBar(state, remember(viewModel) { viewModel.asVoiceBackend() }, modifier)
}

@Composable
fun VoiceBar(state: AppUiState, backend: VoiceBackend, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember(backend) {
        VoiceController(AndroidSttEngine(context), AndroidTtsEngine(context), backend)
    }
    DisposableEffect(controller) { onDispose { controller.release() } }

    LaunchedEffect(state.isRunning, state.messages.size, state.pendingApproval?.approvalId) {
        controller.onAgentSnapshot(state.toAgentSnapshot())
    }

    val phase by controller.phase.collectAsStateWithLifecycle()
    val partial by controller.partial.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    var speakOn by remember { mutableStateOf(true) }
    controller.speakReplies = speakOn

    var setupHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        setupHint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val rootfs = com.jarves.mh.runtime.RuntimeInstaller(context.applicationContext).installedRuntime().rootfs
                (com.jarves.mh.phone.PhoneSetup.start(context.applicationContext, rootfs) as? com.jarves.mh.phone.HelperProvisioner.Result.Needs)?.what
            }.getOrNull()
        }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.onMicPressed()
    }
    fun press() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) controller.onMicPressed() else askPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onClick = ::press) {
            Icon(if (phase == VoicePhase.IDLE) Icons.Filled.Mic else Icons.Filled.Stop, contentDescription = "Voice")
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(error ?: when (phase) {
                VoicePhase.IDLE -> "Tap to talk"
                VoicePhase.LISTENING -> "Listening…"
                VoicePhase.THINKING -> "Working…"
                VoicePhase.SPEAKING -> "Speaking (tap to interrupt)"
                VoicePhase.AWAITING_APPROVAL -> "Say yes or no"
            }, style = MaterialTheme.typography.labelLarge)
            setupHint?.let { Text("Phone control: $it", style = MaterialTheme.typography.bodySmall, maxLines = 3) }
            if (partial.isNotBlank()) Text(partial, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Switch(checked = speakOn, onCheckedChange = { speakOn = it })
    }
}
