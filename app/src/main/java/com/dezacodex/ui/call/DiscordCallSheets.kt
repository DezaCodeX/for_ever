package com.dezacodex.ui.call

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dezacodex.call.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantListSheet(
    state: CallUiState,
    onDismiss: () -> Unit,
    onPinToggle: (String) -> Unit,
    onFocusParticipant: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141824),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Participants (${state.participants.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color(0xFFC084FC))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                items(state.participants, key = { it.participantId }) { participant ->
                    val isActiveSpeaker = participant.participantId == state.activeSpeakerId || participant.isSpeaking
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E2436))
                            .border(
                                width = if (isActiveSpeaker) 1.5.dp else 1.dp,
                                color = if (isActiveSpeaker) Color(0xFF22C55E) else Color(0xFF2B3245),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (participant.isLocal) Color(0xFF9333EA) else Color(0xFFFF4B6E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = participant.displayName.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (participant.isLocal) "${participant.displayName} (You)" else participant.displayName,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (participant.isPinned) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("PINNED", fontSize = 9.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Black)
                                    }
                                }
                                Text(
                                    text = buildString {
                                        append(if (participant.isMicMuted) "Muted" else if (isActiveSpeaker) "Speaking" else "Mic On")
                                        append(" • ")
                                        append(if (participant.isWebcamEnabled) "Cam On" else "Cam Off")
                                        if (participant.isScreenSharing) append(" • Sharing Screen")
                                    },
                                    fontSize = 11.sp,
                                    color = if (isActiveSpeaker) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onFocusParticipant(participant.participantId) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CenterFocusStrong,
                                    contentDescription = "Focus ${participant.displayName}",
                                    tint = Color(0xFF38BDF8)
                                )
                            }
                            IconButton(
                                onClick = { onPinToggle(participant.participantId) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("pin_participant_${participant.participantId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = if (participant.isPinned) "Unpin" else "Pin",
                                    tint = if (participant.isPinned) Color(0xFFFBBF24) else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAndQualitySettingsSheet(
    state: CallUiState,
    onDismiss: () -> Unit,
    onSelectCamera: (String) -> Unit,
    onSelectMicrophone: (String) -> Unit,
    onSelectAudioOutput: (AudioOutputDevice) -> Unit,
    onSelectVadSensitivity: (VadSensitivity) -> Unit,
    onSelectQualityPreset: (VideoQualityPreset) -> Unit,
    onUpdateSignalingUrl: (String) -> Unit
) {
    var urlInput by remember(state.signalingServerUrl) { mutableStateOf(state.signalingServerUrl) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141824),
        contentColor = Color.White
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Audio, Video & Bandwidth Controls",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Speaker / Audio Output selector
            item {
                Text("Speaker / Audio Output", fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.availableAudioOutputs.forEach { output ->
                        val selected = state.selectedAudioOutput == output
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectAudioOutput(output) },
                            label = { Text(output.label, fontSize = 11.sp) },
                            modifier = Modifier.testTag("audio_output_${output.name}")
                        )
                    }
                }
            }

            // Camera device selector
            if (state.availableCameras.isNotEmpty()) {
                item {
                    Text("Camera Device", fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.availableCameras.forEach { cam ->
                            FilterChip(
                                selected = state.selectedCameraId == cam.deviceId,
                                onClick = { onSelectCamera(cam.deviceId) },
                                label = { Text(cam.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Microphone device selector
            item {
                Text("Microphone Input", fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.availableMicrophones.forEach { mic ->
                        FilterChip(
                            selected = state.selectedMicId == mic.deviceId,
                            onClick = { onSelectMicrophone(mic.deviceId) },
                            label = { Text(mic.label, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // VAD Sensitivity
            item {
                Text("Voice Activity Detection (VAD) Sensitivity", fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VadSensitivity.entries.forEach { sens ->
                        FilterChip(
                            selected = state.vadSensitivity == sens,
                            onClick = { onSelectVadSensitivity(sens) },
                            label = { Text(sens.label, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Simulcast / Quality Preset
            item {
                Text("WebRTC Simulcast & Quality", fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoQualityPreset.entries.forEach { preset ->
                        val active = state.qualityPreset == preset
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Color(0xFF7E22CE).copy(alpha = 0.25f) else Color(0xFF1E2436))
                                .border(
                                    1.dp,
                                    if (active) Color(0xFFC084FC) else Color(0xFF2B3245),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectQualityPreset(preset) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(preset.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("${preset.maxBitrateBps / 1000} kbps", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                    }
                }
            }

            // Network stats summary
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2436)),
                    border = BorderStroke(1.dp, Color(0xFF2B3245)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Live WebRTC Telemetry", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "RTT: ${state.networkStats.rttMs} ms  •  Loss: ${"%.1f".format(state.networkStats.packetLossPercent)}%  •  Bitrate: ${state.networkStats.outgoingBitrateKbps} kbps",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Simulcast Layer: ${state.networkStats.activeSimulcastLayer.uppercase()}  •  Degradation: ${state.networkStats.degradationPreference}",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Signaling server URL
            item {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("WebSocket Signaling Server URL (optional)", fontSize = 11.sp) },
                    placeholder = { Text("wss://your-signaling.example.com/ws") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = { onUpdateSignalingUrl(urlInput) }) {
                            Text("Save", color = Color(0xFFC084FC))
                        }
                    }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun MinimizedCallFloatingBar(
    state: CallUiState,
    spotlightParticipant: ParticipantStream?,
    onExpand: () -> Unit,
    onToggleMic: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onExpand)
            .testTag("minimized_call_bar"),
        color = Color(0xFF141824),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 8.dp,
        border = BorderStroke(
            width = 1.5.dp,
            color = if (spotlightParticipant?.isSpeaking == true) Color(0xFF22C55E) else Color(0xFF7E22CE)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (spotlightParticipant?.isSpeaking == true) Color(0xFF22C55E) else Color(0xFF9333EA)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (spotlightParticipant?.displayName?.take(1) ?: "C").uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = spotlightParticipant?.displayName ?: "Active Call",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatElapsed(state.elapsedSeconds) +
                            if (state.isScreenSharing) " • Screen Sharing" else " • Tap to return",
                        color = Color(0xFF4ADE80),
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = onToggleMic,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(if (state.localMicMuted) Color(0xFFEF4444) else Color(0xFF2B3245))
                ) {
                    Icon(
                        imageVector = if (state.localMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Toggle Mic",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onHangUp,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

internal fun formatElapsed(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
