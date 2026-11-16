package com.dezacodex.ui.call

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dezacodex.call.*
import com.dezacodex.data.LoversProfile
import com.dezacodex.data.User
import com.dezacodex.ui.HeartViewModel

@Composable
fun DiscordCallsView(
    viewModel: HeartViewModel,
    user: User,
    profile: LoversProfile
) {
    val context = LocalContext.current
    val callState by viewModel.callUiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        viewModel.callController.updatePermissions(hasCamera = hasCam, hasMic = hasMic)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cam = results[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val mic = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        viewModel.callController.updatePermissions(hasCamera = cam, hasMic = mic)
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startScreenShare(data, captureAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        } else {
            viewModel.callController.clearCallError()
        }
    }

    fun requestMediaPermissionsIfNeeded(includeCamera: Boolean) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (includeCamera && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // System Picture-in-Picture mode renders only the clean spotlight stream
    if (callState.isInPipMode && callState.callStatus == "CONNECTED") {
        val spotlight = viewModel.callController.getSpotlightParticipant()
        PipSpotlightSurface(
            participant = spotlight,
            engine = viewModel.callController.webRtcEngine
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (callState.callStatus == "CONNECTED" && callState.callType == "VIDEO") {
                    Brush.verticalGradient(listOf(Color(0xFF0B0E14), Color(0xFF141824)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFFFFF9FA), Color(0xFFFDF2F8)))
                }
            )
    ) {
        when (callState.callStatus) {
            "IDLE" -> {
                CallLobbyCard(
                    callType = callState.callType,
                    signalingState = callState.signalingState,
                    onVoiceCall = {
                        requestMediaPermissionsIfNeeded(includeCamera = false)
                        viewModel.initiateCall("VOICE")
                    },
                    onVideoCall = {
                        requestMediaPermissionsIfNeeded(includeCamera = true)
                        viewModel.initiateCall("VIDEO")
                    },
                    onSimulateIncoming = {
                        requestMediaPermissionsIfNeeded(includeCamera = true)
                        viewModel.receiveSimulatedCall("VIDEO")
                    }
                )
            }
            "OUTGOING", "INCOMING" -> {
                RingingCallCard(
                    status = callState.callStatus,
                    callType = callState.callType,
                    partnerName = callState.participants.firstOrNull { !it.isLocal }?.displayName
                        ?: user.connectedPartnerEmail?.substringBefore("@") ?: "Soulmate",
                    onAccept = {
                        requestMediaPermissionsIfNeeded(includeCamera = callState.callType == "VIDEO")
                        viewModel.acceptIncomingCall()
                    },
                    onHangUp = { viewModel.hangUpCall() }
                )
            }
            "CONNECTED" -> {
                if (callState.callType == "VOICE") {
                    VoiceCallRoom(
                        state = callState,
                        activeWallpaper = profile.voiceCallWallpaper,
                        onSelectWallpaper = { viewModel.updateVoiceWallpaper(it) },
                        onToggleMic = { viewModel.toggleMic() },
                        onSelectOutput = { viewModel.selectAudioOutput(it) },
                        onMinimize = { viewModel.callController.setMinimizedCallWindow(true) },
                        onHangUp = { viewModel.hangUpCall() }
                    )
                } else {
                    DiscordVideoCallStage(
                        state = callState,
                        engine = viewModel.callController.webRtcEngine,
                        onToggleMic = { viewModel.toggleMic() },
                        onToggleCamera = {
                            if (!callState.hasCameraPermission) {
                                requestMediaPermissionsIfNeeded(includeCamera = true)
                            }
                            viewModel.toggleCameraSelf()
                        },
                        onStartScreenShare = {
                            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                            if (mgr != null) {
                                screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
                            }
                        },
                        onStopScreenShare = { viewModel.stopScreenShare() },
                        onSwitchCamera = { viewModel.switchCallCamera() },
                        onSetLayoutMode = { viewModel.setCallLayoutMode(it) },
                        onSetFocusTarget = { target, pId -> viewModel.setCallFocusTarget(target, pId) },
                        onPinParticipant = { viewModel.togglePinParticipant(it) },
                        onToggleHideNonVideo = { viewModel.toggleHideNonVideo() },
                        onShowParticipants = { viewModel.callController.setShowParticipantsSheet(true) },
                        onShowSettings = { viewModel.callController.setShowDeviceSettingsSheet(true) },
                        onMinimize = { viewModel.callController.setMinimizedCallWindow(true) },
                        onEnterPip = {
                            val activity = context.findComponentActivity()
                            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val params = PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational(9, 16))
                                    .build()
                                activity.enterPictureInPictureMode(params)
                            }
                        },
                        onDismissError = { viewModel.callController.clearCallError() },
                        onHangUp = { viewModel.hangUpCall() }
                    )
                }
            }
        }
    }

    if (callState.showParticipantsSheet) {
        ParticipantListSheet(
            state = callState,
            onDismiss = { viewModel.callController.setShowParticipantsSheet(false) },
            onPinToggle = { viewModel.togglePinParticipant(it) },
            onFocusParticipant = { pId ->
                viewModel.setCallFocusTarget(FocusTarget.PARTICIPANT_CAMERA, pId)
                viewModel.callController.setShowParticipantsSheet(false)
            }
        )
    }

    if (callState.showDeviceSettingsSheet) {
        DeviceAndQualitySettingsSheet(
            state = callState,
            onDismiss = { viewModel.callController.setShowDeviceSettingsSheet(false) },
            onSelectCamera = { viewModel.switchCallCamera(it) },
            onSelectMicrophone = { viewModel.callController.selectMicrophone(it) },
            onSelectAudioOutput = { viewModel.selectAudioOutput(it) },
            onSelectVadSensitivity = { viewModel.setVadSensitivity(it) },
            onSelectQualityPreset = { viewModel.setVideoQualityPreset(it) },
            onUpdateSignalingUrl = { viewModel.callController.updateSignalingServerUrl(it) }
        )
    }
}

@Composable
private fun CallLobbyCard(
    callType: String,
    signalingState: SignalingConnectionState,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSimulateIncoming: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFFF3E8FF)),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color(0xFFF5F3FF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (callType == "VOICE") Icons.Default.Phone else Icons.Default.Videocam,
                        contentDescription = "Call icon",
                        tint = Color(0xFF7E22CE),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Couples Private Calling Booth",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF5B21B6),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Discord-grade multi-stream WebRTC calls with simultaneous webcam & screen sharing, real-time VAD active speaker focus, simulcast quality control, and PiP.",
                    fontSize = 12.sp,
                    color = Color(0xFF6B21A8).copy(alpha = 0.8f),
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFFF5F3FF),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Signaling: ${signalingState.label}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7E22CE),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onVoiceCall,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("btn_start_voice_call"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                    ) {
                        Icon(Icons.Default.Phone, "Voice Call", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Voice Call", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Button(
                        onClick = onVideoCall,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("btn_start_video_call"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                    ) {
                        Icon(Icons.Default.Videocam, "Video Call", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Video Call", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onSimulateIncoming,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("btn_simulate_incoming_call"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFC084FC))
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = "Simulate Incoming Call", tint = Color(0xFF7E22CE))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simulate Lover Calling You", color = Color(0xFF7E22CE), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RingingCallCard(
    status: String,
    callType: String,
    partnerName: String,
    onAccept: () -> Unit,
    onHangUp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
            border = BorderStroke(1.dp, Color(0xFF5B21B6)),
            shape = RoundedCornerShape(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (status == "OUTGOING") "RINGING SANCTUARY HANDSHAKE..." else "INCOMING SECURE CALL REQUEST...",
                    color = Color(0xFFC084FC),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = partnerName.take(1).uppercase(),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = if (status == "OUTGOING") "Calling $partnerName..." else "$partnerName is calling",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 19.sp
                )
                Text(
                    text = if (callType == "VIDEO") "Multi-Stream Video & Screen Share" else "Encrypted Voice Call",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(30.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (status == "INCOMING") {
                        IconButton(
                            onClick = onAccept,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                                .testTag("btn_accept_call")
                        ) {
                            Icon(Icons.Default.Phone, "Answer Call", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                    IconButton(
                        onClick = onHangUp,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444))
                            .testTag("btn_decline_call")
                    ) {
                        Icon(Icons.Default.CallEnd, "End Call", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCallRoom(
    state: CallUiState,
    activeWallpaper: String,
    onSelectWallpaper: (String) -> Unit,
    onToggleMic: () -> Unit,
    onSelectOutput: (AudioOutputDevice) -> Unit,
    onMinimize: () -> Unit,
    onHangUp: () -> Unit
) {
    val wallpaperBrush = when (activeWallpaper) {
        "Lovely Lavender" -> Brush.verticalGradient(listOf(Color(0xFFE9D5FF), Color(0xFFC084FC), Color(0xFF5B21B6)))
        "Ocean Breeze" -> Brush.verticalGradient(listOf(Color(0xFF0EA5E9), Color(0xFF0369A1), Color(0xFF0C4A6E)))
        "Sunset Rose" -> Brush.verticalGradient(listOf(Color(0xFFFDA4AF), Color(0xFFF43F5E), Color(0xFF1E1B4B)))
        "Forest Serenity" -> Brush.verticalGradient(listOf(Color(0xFF4ADE80), Color(0xFF15803D), Color(0xFF022C22)))
        else -> Brush.verticalGradient(listOf(Color(0xFF2E1065), Color(0xFF0F172A), Color(0xFF020617)))
    }

    val local = state.participants.find { it.isLocal }
    val speaking = local?.isSpeaking == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(wallpaperBrush)
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            text = "Voice Call • ${formatElapsed(state.elapsedSeconds)}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.PictureInPictureAlt, "Minimize Call", tint = Color.White)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .border(
                                width = if (speaking) 4.dp else 1.dp,
                                color = if (speaking) Color(0xFF22C55E) else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = "Voice Call Heart", tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (speaking) "Voice Active (VAD)" else "Connected",
                        color = if (speaking) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Voice Call Wallpaper",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Lovely Lavender", "Ocean Breeze", "Sunset Rose", "Forest Serenity", "Midnight").forEach { wp ->
                            val isAct = activeWallpaper == wp
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isAct) Color(0xFFC084FC) else Color.White.copy(alpha = 0.18f))
                                    .clickable { onSelectWallpaper(wp) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(wp.substringBefore(" "), fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlCircleButton(
                        icon = if (state.localMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (state.localMicMuted) "Unmute" else "Mute",
                        active = !state.localMicMuted,
                        danger = state.localMicMuted,
                        testTag = "btn_toggle_mic",
                        onClick = onToggleMic
                    )
                    CallControlCircleButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = state.selectedAudioOutput.label,
                        active = state.selectedAudioOutput == AudioOutputDevice.SPEAKER,
                        testTag = "btn_voice_speaker",
                        onClick = {
                            val next = if (state.selectedAudioOutput == AudioOutputDevice.SPEAKER) AudioOutputDevice.EARPIECE else AudioOutputDevice.SPEAKER
                            onSelectOutput(next)
                        }
                    )
                    CallControlCircleButton(
                        icon = Icons.Default.CallEnd,
                        label = "Leave",
                        danger = true,
                        testTag = "btn_end_call",
                        onClick = onHangUp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscordVideoCallStage(
    state: CallUiState,
    engine: WebRtcCallEngine,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onStartScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSetLayoutMode: (CallLayoutMode) -> Unit,
    onSetFocusTarget: (FocusTarget, String?) -> Unit,
    onPinParticipant: (String) -> Unit,
    onToggleHideNonVideo: () -> Unit,
    onShowParticipants: () -> Unit,
    onShowSettings: () -> Unit,
    onMinimize: () -> Unit,
    onEnterPip: () -> Unit,
    onDismissError: () -> Unit,
    onHangUp: () -> Unit
) {
    val visibleParticipants = remember(state.participants, state.hideNonVideoParticipants, state.pinnedParticipantId) {
        if (!state.hideNonVideoParticipants) {
            state.participants
        } else {
            val filtered = state.participants.filter {
                it.hasAnyVideo || it.participantId == state.pinnedParticipantId || it.isSpeaking
            }
            filtered.ifEmpty { state.participants }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top HUD bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Live • ${formatElapsed(state.elapsedSeconds)}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFF1E2436),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = state.networkStats.activeSimulcastLayer.uppercase(),
                            color = Color(0xFF38BDF8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = state.signalingState.label,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Grid / Focus toggle
                IconButton(
                    onClick = {
                        onSetLayoutMode(
                            if (state.layoutMode == CallLayoutMode.GRID) CallLayoutMode.FOCUS
                            else CallLayoutMode.GRID
                        )
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_toggle_layout")
                ) {
                    Icon(
                        imageVector = if (state.layoutMode == CallLayoutMode.GRID) Icons.Default.ViewSidebar else Icons.Default.GridView,
                        contentDescription = "Toggle Grid or Focus View",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Surface(
                    color = Color(0xFF1E2436),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF2B3245))
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        LayoutChip(
                            label = "Grid",
                            selected = state.layoutMode == CallLayoutMode.GRID,
                            testTag = "btn_layout_grid",
                            onClick = { onSetLayoutMode(CallLayoutMode.GRID) }
                        )
                        LayoutChip(
                            label = "Focus",
                            selected = state.layoutMode == CallLayoutMode.FOCUS,
                            testTag = "btn_layout_focus",
                            onClick = { onSetLayoutMode(CallLayoutMode.FOCUS) }
                        )
                    }
                }

                IconButton(
                    onClick = onToggleHideNonVideo,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("btn_toggle_non_video")
                ) {
                    Icon(
                        imageVector = if (state.hideNonVideoParticipants) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle non-video participants",
                        tint = if (state.hideNonVideoParticipants) Color(0xFFFBBF24) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onShowParticipants,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("btn_participants_list")
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = "View participants",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    IconButton(
                        onClick = onEnterPip,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("btn_enter_pip")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Picture in Picture",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("btn_minimize_call")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize Call",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Error or permission notice banner
        state.callError?.let { err ->
            Surface(
                color = Color(0xFF7F1D1D),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(err, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissError, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Main responsive video area (Grid or Focus)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isWide = maxWidth >= 600.dp
            if (state.layoutMode == CallLayoutMode.GRID) {
                GridVideoLayout(
                    participants = visibleParticipants,
                    activeSpeakerId = state.activeSpeakerId,
                    isWide = isWide,
                    engine = engine,
                    onPinToggle = onPinParticipant,
                    onFocus = { pId -> onSetFocusTarget(FocusTarget.PARTICIPANT_CAMERA, pId) }
                )
            } else {
                FocusVideoLayout(
                    state = state,
                    participants = visibleParticipants,
                    isWide = isWide,
                    engine = engine,
                    onSetFocusTarget = onSetFocusTarget,
                    onPinToggle = onPinParticipant
                )
            }
        }

        // Bottom control dock
        Surface(
            color = Color(0xFF141824),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF2B3245)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControlCircleButton(
                    icon = if (state.localMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (state.localMicMuted) "Muted" else "Mic",
                    active = !state.localMicMuted,
                    danger = state.localMicMuted,
                    testTag = "btn_toggle_mic",
                    onClick = onToggleMic
                )
                CallControlCircleButton(
                    icon = if (state.localCamEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (state.localCamEnabled) "Cam" else "Cam Off",
                    active = state.localCamEnabled,
                    danger = !state.localCamEnabled,
                    testTag = "btn_toggle_cam",
                    onClick = onToggleCamera
                )
                CallControlCircleButton(
                    icon = if (state.isScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare else Icons.AutoMirrored.Filled.ScreenShare,
                    label = if (state.isScreenSharing) "Stop" else "Share",
                    active = state.isScreenSharing,
                    highlightColor = Color(0xFF22C55E),
                    testTag = "btn_toggle_screen_share",
                    onClick = { if (state.isScreenSharing) onStopScreenShare() else onStartScreenShare() }
                )
                CallControlCircleButton(
                    icon = Icons.Default.Cameraswitch,
                    label = "Flip",
                    active = false,
                    testTag = "btn_switch_camera",
                    onClick = onSwitchCamera
                )
                CallControlCircleButton(
                    icon = Icons.Default.Tune,
                    label = "Devices",
                    active = false,
                    testTag = "btn_call_settings",
                    onClick = onShowSettings
                )
                CallControlCircleButton(
                    icon = Icons.Default.CallEnd,
                    label = "Leave",
                    danger = true,
                    testTag = "btn_end_call",
                    onClick = onHangUp
                )
            }
        }
    }
}

@Composable
private fun GridVideoLayout(
    participants: List<ParticipantStream>,
    activeSpeakerId: String?,
    isWide: Boolean,
    engine: WebRtcCallEngine,
    onPinToggle: (String) -> Unit,
    onFocus: (String) -> Unit
) {
    // Expand independent screen shares into separate tiles alongside webcams
    val tiles = remember(participants) {
        buildList {
            participants.forEach { p ->
                add(TileItem(participant = p, isScreenTile = false))
                if (p.isScreenSharing && p.screenTrackId != null) {
                    add(TileItem(participant = p, isScreenTile = true))
                }
            }
        }
    }

    val columns = when {
        tiles.size <= 1 -> 1
        tiles.size == 2 && !isWide -> 1
        tiles.size <= 4 -> 2
        else -> if (isWide) 3 else 2
    }

    val rows = tiles.chunked(columns)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { rowTiles ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowTiles.forEach { tile ->
                    ParticipantVideoTile(
                        participant = tile.participant,
                        isScreenTile = tile.isScreenTile,
                        isActiveSpeaker = !tile.isScreenTile &&
                            (tile.participant.isSpeaking || tile.participant.participantId == activeSpeakerId),
                        engine = engine,
                        onPinToggle = { onPinToggle(tile.participant.participantId) },
                        onClick = { onFocus(tile.participant.participantId) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                if (rowTiles.size < columns) {
                    repeat(columns - rowTiles.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusVideoLayout(
    state: CallUiState,
    participants: List<ParticipantStream>,
    isWide: Boolean,
    engine: WebRtcCallEngine,
    onSetFocusTarget: (FocusTarget, String?) -> Unit,
    onPinToggle: (String) -> Unit
) {
    val screenSharer = participants.firstOrNull { it.isScreenSharing && it.screenTrackId != null }
    val showScreenInMain = state.focusTarget == FocusTarget.SCREEN_SHARE && screenSharer != null

    val mainParticipant = when {
        showScreenInMain -> screenSharer
        state.pinnedParticipantId != null -> participants.find { it.participantId == state.pinnedParticipantId } ?: participants.firstOrNull()
        state.focusedParticipantId != null -> participants.find { it.participantId == state.focusedParticipantId } ?: participants.firstOrNull()
        else -> participants.firstOrNull { it.participantId == state.activeSpeakerId } ?: participants.firstOrNull()
    }

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(0.75f).fillMaxHeight()) {
                FocusMainViewport(
                    mainParticipant = mainParticipant,
                    showScreen = showScreenInMain,
                    hasAnyScreenShare = screenSharer != null,
                    activeSpeakerId = state.activeSpeakerId,
                    engine = engine,
                    onSwitchFocusType = {
                        val nextTarget = if (showScreenInMain) FocusTarget.PARTICIPANT_CAMERA else FocusTarget.SCREEN_SHARE
                        onSetFocusTarget(nextTarget, mainParticipant?.participantId)
                    },
                    onPinToggle = { mainParticipant?.let { onPinToggle(it.participantId) } }
                )
            }
            LazyColumn(
                modifier = Modifier.weight(0.25f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(participants, key = { it.participantId }) { p ->
                    ParticipantVideoTile(
                        participant = p,
                        isScreenTile = false,
                        isActiveSpeaker = p.isSpeaking || p.participantId == state.activeSpeakerId,
                        engine = engine,
                        onPinToggle = { onPinToggle(p.participantId) },
                        onClick = { onSetFocusTarget(FocusTarget.PARTICIPANT_CAMERA, p.participantId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().weight(0.72f)) {
                FocusMainViewport(
                    mainParticipant = mainParticipant,
                    showScreen = showScreenInMain,
                    hasAnyScreenShare = screenSharer != null,
                    activeSpeakerId = state.activeSpeakerId,
                    engine = engine,
                    onSwitchFocusType = {
                        val nextTarget = if (showScreenInMain) FocusTarget.PARTICIPANT_CAMERA else FocusTarget.SCREEN_SHARE
                        onSetFocusTarget(nextTarget, mainParticipant?.participantId)
                    },
                    onPinToggle = { mainParticipant?.let { onPinToggle(it.participantId) } }
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().weight(0.28f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(participants, key = { it.participantId }) { p ->
                    ParticipantVideoTile(
                        participant = p,
                        isScreenTile = false,
                        isActiveSpeaker = p.isSpeaking || p.participantId == state.activeSpeakerId,
                        engine = engine,
                        onPinToggle = { onPinToggle(p.participantId) },
                        onClick = { onSetFocusTarget(FocusTarget.PARTICIPANT_CAMERA, p.participantId) },
                        modifier = Modifier
                            .width(140.dp)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusMainViewport(
    mainParticipant: ParticipantStream?,
    showScreen: Boolean,
    hasAnyScreenShare: Boolean,
    activeSpeakerId: String?,
    engine: WebRtcCallEngine,
    onSwitchFocusType: () -> Unit,
    onPinToggle: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (mainParticipant != null) {
            ParticipantVideoTile(
                participant = mainParticipant,
                isScreenTile = showScreen,
                isActiveSpeaker = !showScreen && (mainParticipant.isSpeaking || mainParticipant.participantId == activeSpeakerId),
                engine = engine,
                onPinToggle = onPinToggle,
                onClick = {},
                modifier = Modifier.fillMaxSize()
            )
        }
        if (hasAnyScreenShare) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clickable(onClick = onSwitchFocusType)
                    .testTag("btn_switch_focus_target")
            ) {
                Text(
                    text = if (showScreen) "Viewing Screen • Switch to Camera" else "Viewing Camera • Switch to Screen",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ParticipantVideoTile(
    participant: ParticipantStream,
    isScreenTile: Boolean,
    isActiveSpeaker: Boolean,
    engine: WebRtcCallEngine,
    onPinToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            isScreenTile -> Color(0xFF38BDF8)
            isActiveSpeaker -> Color(0xFF22C55E)
            participant.isPinned -> Color(0xFFFBBF24)
            else -> Color(0xFF2B3245)
        },
        label = "tileBorderColor"
    )
    val borderWidth = if (isActiveSpeaker || participant.isPinned) 3.dp else 1.dp
    val trackId = if (isScreenTile) participant.screenTrackId else participant.webcamTrackId
    val videoActive = if (isScreenTile) participant.isScreenSharing else participant.isWebcamEnabled

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A1F2E))
            .border(borderWidth, borderColor, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("video_tile_${participant.participantId}_${if (isScreenTile) "screen" else "cam"}")
    ) {
        if (videoActive && trackId != null) {
            WebRtcVideoSurface(
                trackId = trackId,
                engine = engine,
                isScreenShare = isScreenTile,
                mirror = participant.isLocal && !isScreenTile,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Clean avatar fallback when camera is off
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (participant.isLocal) Color(0xFF9333EA) else Color(0xFFFF4B6E)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = participant.displayName.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isScreenTile) "Screen Share Active" else "Camera Off",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Top-right Pin button
        if (!isScreenTile) {
            IconButton(
                onClick = onPinToggle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag("tile_pin_${participant.participantId}")
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = if (participant.isPinned) "Unpin participant" else "Pin participant",
                    tint = if (participant.isPinned) Color(0xFFFBBF24) else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Bottom overlay bar: name, mic status, cam status
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (isActiveSpeaker) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (isScreenTile) "${participant.displayName}'s Screen"
                    else if (participant.isLocal) "${participant.displayName} (You)"
                    else participant.displayName,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (participant.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (participant.isMicMuted) "Muted" else "Mic active",
                    tint = if (participant.isMicMuted) Color(0xFFEF4444) else if (isActiveSpeaker) Color(0xFF22C55E) else Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Icon(
                    imageVector = if (participant.isWebcamEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = if (participant.isWebcamEnabled) "Camera On" else "Camera Off",
                    tint = if (participant.isWebcamEnabled) Color.White else Color(0xFF94A3B8),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun PipSpotlightSurface(
    participant: ParticipantStream?,
    engine: WebRtcCallEngine
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14)),
        contentAlignment = Alignment.Center
    ) {
        if (participant != null) {
            val useScreen = participant.isScreenSharing && participant.screenTrackId != null
            val trackId = if (useScreen) participant.screenTrackId else participant.webcamTrackId
            if (trackId != null && (useScreen || participant.isWebcamEnabled)) {
                WebRtcVideoSurface(
                    trackId = trackId,
                    engine = engine,
                    isScreenShare = useScreen,
                    mirror = participant.isLocal && !useScreen,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF9333EA))
                            .border(
                                width = if (participant.isSpeaking) 3.dp else 1.dp,
                                color = if (participant.isSpeaking) Color(0xFF22C55E) else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(participant.displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(participant.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LayoutChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF7E22CE) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CallControlCircleButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    highlightColor: Color = Color(0xFF7E22CE),
    testTag: String,
    onClick: () -> Unit
) {
    val bgColor = when {
        danger -> Color(0xFFEF4444)
        active -> highlightColor
        else -> Color(0xFF2B3245)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(bgColor)
                .testTag(testTag)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(label, color = Color(0xFFCBD5E1), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

private data class TileItem(
    val participant: ParticipantStream,
    val isScreenTile: Boolean
)
