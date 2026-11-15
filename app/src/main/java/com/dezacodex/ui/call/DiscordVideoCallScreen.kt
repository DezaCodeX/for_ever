package com.dezacodex.ui.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dezacodex.call.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Discord-style Real-Time Multi-Stream Video Conferencing Experience:
 * - Simultaneous Screen Share + Webcam streams (independent tracks, screen share never replaces webcam)
 * - Dynamic Grid View & Focus View (switchable without dropping call)
 * - Active Speaker green ring highlight & smooth debounced prioritization
 * - Participant Pinning overriding auto-switching
 * - Auto Camera Switching in Compact/Floating PiP mode
 * - Hide Non-Video Participants, Output Audio Routing, Participant List, Connection Stats & Settings Modal
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordVideoCallScreen(
    engine: WebRtcCallEngine,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by engine.uiState.collectAsState()
    val localCamTrack by engine.localCamera.collectAsState()
    val localScreenTrack by engine.localScreen.collectAsState()
    val remoteCamTracks by engine.remoteCameras.collectAsState()
    val remoteScreenTracks by engine.remoteScreens.collectAsState()
    val vadState by engine.vad.state.collectAsState()
    val context = LocalContext.current

    var showParticipantsSheet by remember { mutableStateOf(false) }
    var showStatsSheet by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAudioRouteMenu by remember { mutableStateOf(false) }
    var shareScreenAudio by remember { mutableStateOf(true) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            engine.setPermissionWarning(
                "Missing permissions (${denied.joinToString()}). Some streams may remain off."
            )
        } else {
            engine.setPermissionWarning(null)
        }
    }

    val screenProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            engine.startScreenSharing(result.data!!, shareScreenAudio = shareScreenAudio)
        } else {
            engine.setPermissionWarning("Screen sharing permission canceled.")
        }
    }

    LaunchedEffect(state.callRoomId) {
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    val visibleParticipants = remember(state.participants, state.hideNonVideoParticipants) {
        if (state.hideNonVideoParticipants) {
            state.participants.filter { it.isCameraEnabled || it.isScreenSharing || it.isLocal }
        } else {
            state.participants
        }
    }

    // Build flattened multi-stream tiles so a participant sharing screen AND webcam yields BOTH independent tiles!
    val streamTiles = remember(visibleParticipants, state.pinnedUid) {
        buildList {
            visibleParticipants.forEach { p ->
                if (p.isScreenSharing) {
                    add(
                        VideoTileModel(
                            tileId = "${p.uid}_screen",
                            participant = p,
                            isScreenShare = true
                        )
                    )
                }
                add(
                    VideoTileModel(
                        tileId = "${p.uid}_cam",
                        participant = p,
                        isScreenShare = false
                    )
                )
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("discord_video_call_screen"),
        color = Color(0xFF111216)
    ) {
        if (state.isCompactPipMode) {
            CompactPipCallOverlay(
                state = state,
                featuredUid = engine.resolveFeaturedParticipantUid(),
                eglContext = engine.sharedEglContext,
                localCamTrack = localCamTrack,
                localScreenTrack = localScreenTrack,
                remoteCamTracks = remoteCamTracks,
                remoteScreenTracks = remoteScreenTracks,
                onExpand = { engine.setCompactPipMode(false) },
                onToggleMute = { engine.toggleMute() },
                onHangUp = onHangUp
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                // Top Conference Bar
                CallTopBar(
                    state = state,
                    onToggleLayout = { engine.toggleLayoutMode() },
                    onToggleCompactPip = { engine.setCompactPipMode(true) },
                    onShowParticipants = { showParticipantsSheet = true },
                    onShowStats = { showStatsSheet = true },
                    onShowSettings = { showSettingsDialog = true }
                )

                AnimatedVisibility(visible = !state.permissionWarning.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF451A03),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Permission alert",
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = state.permissionWarning ?: "",
                                color = Color(0xFFFEF3C7),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { engine.setPermissionWarning(null) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss warning", tint = Color.White)
                            }
                        }
                    }
                }

                // Dynamic Video Stage: Grid View vs Focus View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (state.layoutMode == CallLayoutMode.GRID) {
                        val columns = when {
                            streamTiles.size <= 1 -> 1
                            streamTiles.size <= 4 -> 2
                            else -> 3
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("call_grid_view")
                        ) {
                            items(streamTiles, key = { it.tileId }) { tile ->
                                VideoStreamTile(
                                    tile = tile,
                                    isPinned = state.pinnedUid == tile.participant.uid,
                                    eglContext = engine.sharedEglContext,
                                    track = resolveTrackForTile(
                                        tile,
                                        localCamTrack,
                                        localScreenTrack,
                                        remoteCamTracks,
                                        remoteScreenTracks
                                    ),
                                    onPinClick = { engine.togglePinParticipant(tile.participant.uid) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(if (streamTiles.size <= 2) 0.95f else 0.85f)
                                )
                            }
                        }
                    } else {
                        // FOCUS VIEW: Large featured screen-share/speaker tile + secondary horizontal webcam strip
                        val featuredUid = engine.resolveFeaturedParticipantUid()
                        val mainTile = streamTiles.firstOrNull {
                            it.isScreenShare && (featuredUid == null || it.participant.uid == featuredUid)
                        } ?: streamTiles.firstOrNull { it.participant.uid == featuredUid }
                        ?: streamTiles.firstOrNull()

                        val secondaryTiles = streamTiles.filter { it.tileId != mainTile?.tileId }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("call_focus_view"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (mainTile != null) {
                                VideoStreamTile(
                                    tile = mainTile,
                                    isPinned = state.pinnedUid == mainTile.participant.uid,
                                    eglContext = engine.sharedEglContext,
                                    track = resolveTrackForTile(
                                        mainTile,
                                        localCamTrack,
                                        localScreenTrack,
                                        remoteCamTracks,
                                        remoteScreenTracks
                                    ),
                                    onPinClick = { engine.togglePinParticipant(mainTile.participant.uid) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            }
                            if (secondaryTiles.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.height(132.dp)
                                ) {
                                    items(secondaryTiles, key = { it.tileId }) { tile ->
                                        VideoStreamTile(
                                            tile = tile,
                                            isPinned = state.pinnedUid == tile.participant.uid,
                                            eglContext = engine.sharedEglContext,
                                            track = resolveTrackForTile(
                                                tile,
                                                localCamTrack,
                                                localScreenTrack,
                                                remoteCamTracks,
                                                remoteScreenTracks
                                            ),
                                            onPinClick = { engine.togglePinParticipant(tile.participant.uid) },
                                            modifier = Modifier
                                                .width(148.dp)
                                                .fillMaxHeight()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Conference Control Dock
                val localParticipant = state.participants.firstOrNull { it.isLocal }
                CallControlBar(
                    isMuted = localParticipant?.isAudioMuted ?: false,
                    isCameraOn = localParticipant?.isCameraEnabled ?: false,
                    isScreenSharing = state.isScreenSharing,
                    outputRoute = state.outputRoute,
                    showAudioRouteMenu = showAudioRouteMenu,
                    onToggleMute = { engine.toggleMute() },
                    onToggleCamera = { engine.toggleCamera() },
                    onSwitchCamera = { engine.switchCamera() },
                    onToggleScreenShare = {
                        if (state.isScreenSharing) {
                            engine.stopScreenSharing()
                        } else {
                            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                        }
                    },
                    onOpenAudioMenu = { showAudioRouteMenu = true },
                    onDismissAudioMenu = { showAudioRouteMenu = false },
                    onSelectAudioRoute = { route ->
                        engine.setOutputRoute(route)
                        showAudioRouteMenu = false
                    },
                    onHangUp = onHangUp
                )
            }
        }
    }

    if (showParticipantsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showParticipantsSheet = false },
            containerColor = Color(0xFF1A1C22),
            contentColor = Color.White
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Participants (${state.participants.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.participants, key = { it.uid }) { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (p.isSpeaking) Color(0xFF22C55E) else Color(0xFF6D28D9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(p.displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (p.isLocal) "${p.displayName} (You)" else p.displayName,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = buildString {
                                        append(if (p.isAudioMuted) "Muted" else if (p.isSpeaking) "Speaking" else "Mic on")
                                        if (p.isCameraEnabled) append(" • Cam")
                                        if (p.isScreenSharing) append(" • Sharing Screen")
                                    },
                                    fontSize = 12.sp,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                            IconButton(
                                onClick = { engine.togglePinParticipant(p.uid) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = "Pin ${p.displayName}",
                                    tint = if (state.pinnedUid == p.uid) Color(0xFF22C55E) else Color(0xFF9CA3AF)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showStatsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStatsSheet = false },
            containerColor = Color(0xFF1A1C22),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("WebRTC Connection & Simulcast Stats", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("ICE State: ${state.stats.iceConnectionState}", color = Color(0xFFD1D5DB))
                Text("Simulcast Layer: ${state.stats.activeSimulcastTier.label} (${state.stats.activeSimulcastTier.maxBitrateKbps} kbps)", color = Color(0xFF34D399))
                Text("Network Estimate: ${state.stats.downKbpsEstimate} kbps (${if (state.stats.isMetered) "Metered" else "Unmetered"})", color = Color(0xFFD1D5DB))
                Text("Active Video & Screen Tracks: ${state.stats.activeTracksCount}", color = Color(0xFFD1D5DB))
                Text("Local Mic Level: ${vadState.levelDb.toInt()} dBFS (Threshold ${vadState.thresholdDb.toInt()} dBFS)", color = Color(0xFFD1D5DB))
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = Color(0xFF1F222A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE5E7EB),
            title = { Text("Conference Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Auto-switch speaker camera")
                        Switch(
                            checked = state.autoCamSwitchEnabled,
                            onCheckedChange = { engine.setAutoCamSwitchEnabled(it) }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Hide non-video participants")
                        Switch(
                            checked = state.hideNonVideoParticipants,
                            onCheckedChange = { engine.setHideNonVideoParticipants(it) }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Share device audio with screen")
                        Switch(
                            checked = shareScreenAudio,
                            onCheckedChange = { shareScreenAudio = it }
                        )
                    }
                    Text("Mic VAD Sensitivity: ${vadState.thresholdDb.toInt()} dBFS", fontSize = 13.sp)
                    Slider(
                        value = vadState.thresholdDb.toFloat(),
                        onValueChange = { engine.vad.setSensitivityThresholdDb(it.toDouble()) },
                        valueRange = -70f..-20f
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done", color = Color(0xFFA78BFA))
                }
            }
        )
    }
}

private data class VideoTileModel(
    val tileId: String,
    val participant: ParticipantMediaState,
    val isScreenShare: Boolean
)

private fun resolveTrackForTile(
    tile: VideoTileModel,
    localCamTrack: VideoTrack?,
    localScreenTrack: VideoTrack?,
    remoteCamTracks: Map<String, VideoTrack>,
    remoteScreenTracks: Map<String, VideoTrack>
): VideoTrack? {
    return if (tile.isScreenShare) {
        if (tile.participant.isLocal) localScreenTrack else remoteScreenTracks[tile.participant.uid]
    } else {
        if (tile.participant.isLocal) localCamTrack else remoteCamTracks[tile.participant.uid]
    }
}

@Composable
private fun CallTopBar(
    state: DiscordCallUiState,
    onToggleLayout: () -> Unit,
    onToggleCompactPip: () -> Unit,
    onShowParticipants: () -> Unit,
    onShowStats: () -> Unit,
    onShowSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.status == "CONNECTED") Color(0xFF22C55E) else Color(0xFFF59E0B))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (state.status == "RECONNECTING") "Reconnecting..." else "HeartSync Stage",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Text(
                text = "${state.layoutMode.name} • ${state.stats.activeSimulcastTier.label} • ${state.participants.size} active",
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp
            )
        }

        IconButton(
            onClick = onToggleLayout,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("toggle_layout_button")
        ) {
            Icon(
                imageVector = if (state.layoutMode == CallLayoutMode.GRID) Icons.Default.ViewSidebar else Icons.Default.GridView,
                contentDescription = "Switch grid or focus layout",
                tint = Color.White
            )
        }
        IconButton(
            onClick = onToggleCompactPip,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("toggle_pip_button")
        ) {
            Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Compact PiP view", tint = Color.White)
        }
        IconButton(
            onClick = onShowParticipants,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("participants_list_button")
        ) {
            Icon(Icons.Default.People, contentDescription = "View participants", tint = Color.White)
        }
        IconButton(
            onClick = onShowStats,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("connection_stats_button")
        ) {
            Icon(Icons.Default.NetworkCheck, contentDescription = "Connection quality stats", tint = Color(0xFF34D399))
        }
        IconButton(
            onClick = onShowSettings,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("call_settings_button")
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Call settings", tint = Color.White)
        }
    }
}

@Composable
private fun VideoStreamTile(
    tile: VideoTileModel,
    isPinned: Boolean,
    eglContext: EglBase.Context?,
    track: VideoTrack?,
    onPinClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = tile.participant
    val activeGlow by animateFloatAsState(
        targetValue = if (p.isSpeaking && !p.isAudioMuted) 1f else 0f,
        label = "speakerGlow"
    )
    val borderColor = when {
        p.isSpeaking && !p.isAudioMuted -> Color(0xFF22C55E).copy(alpha = 0.4f + 0.6f * activeGlow)
        tile.isScreenShare -> Color(0xFF8B5CF6)
        isPinned -> Color(0xFF38BDF8)
        else -> Color(0xFF2B2E38)
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPinClick)
            .testTag("video_tile_${tile.tileId}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (p.isSpeaking || isPinned || tile.isScreenShare) 2.5.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F27))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val videoActive = (tile.isScreenShare && p.isScreenSharing) || (!tile.isScreenShare && p.isCameraEnabled)
            if (videoActive && track != null && eglContext != null) {
                WebRtcVideoRenderer(
                    videoTrack = track,
                    eglContext = eglContext,
                    isScreenShare = tile.isScreenShare,
                    mirror = p.isLocal && !tile.isScreenShare,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Clean avatar / screen preview placeholder preserving aspect ratio
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                if (tile.isScreenShare) listOf(Color(0xFF312E81), Color(0xFF1E1B4B))
                                else listOf(Color(0xFF262936), Color(0xFF181A22))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (tile.isScreenShare) Color(0xFF7C3AED) else Color(0xFFEC4899)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (tile.isScreenShare) Icons.Default.ScreenShare else Icons.Default.Person,
                                contentDescription = p.displayName,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (tile.isScreenShare) "${p.displayName}'s Screen" else if (p.isCameraEnabled) "Camera Live" else "Camera Off",
                            color = Color(0xFFD1D5DB),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Bottom Overlay Pill: Name + Badges
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tile.isScreenShare) {
                    Surface(
                        color = Color(0xFF7C3AED),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "LIVE SCREEN",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = if (p.isLocal) "${p.displayName} (You)" else p.displayName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (p.isAudioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (p.isAudioMuted) "Muted" else "Mic active",
                    tint = if (p.isAudioMuted) Color(0xFFEF4444) else if (p.isSpeaking) Color(0xFF22C55E) else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactPipCallOverlay(
    state: DiscordCallUiState,
    featuredUid: String?,
    eglContext: EglBase.Context?,
    localCamTrack: VideoTrack?,
    localScreenTrack: VideoTrack?,
    remoteCamTracks: Map<String, VideoTrack>,
    remoteScreenTracks: Map<String, VideoTrack>,
    onExpand: () -> Unit,
    onToggleMute: () -> Unit,
    onHangUp: () -> Unit
) {
    val featuredParticipant = state.participants.firstOrNull { it.uid == featuredUid }
        ?: state.participants.firstOrNull()
    val localMuted = state.participants.firstOrNull { it.isLocal }?.isAudioMuted ?: false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1015))
            .padding(20.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Compact Auto-Cam PiP Mode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Auto-switching focuses on active speaker (pin overrides)",
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp
            )
        }

        if (featuredParticipant != null) {
            val tile = VideoTileModel(
                tileId = "${featuredParticipant.uid}_pip",
                participant = featuredParticipant,
                isScreenShare = featuredParticipant.isScreenSharing
            )
            Card(
                modifier = Modifier
                    .width(196.dp)
                    .height(256.dp)
                    .testTag("compact_pip_window"),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(2.dp, Color(0xFF22C55E)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F222A))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        VideoStreamTile(
                            tile = tile,
                            isPinned = state.pinnedUid == featuredParticipant.uid,
                            eglContext = eglContext,
                            track = resolveTrackForTile(
                                tile,
                                localCamTrack,
                                localScreenTrack,
                                remoteCamTracks,
                                remoteScreenTracks
                            ),
                            onPinClick = onExpand,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF15171E))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = onToggleMute, modifier = Modifier.size(38.dp)) {
                            Icon(
                                if (localMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Toggle mute",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onExpand,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("expand_pip_button")
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription = "Expand call",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onHangUp, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "Hang up",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlBar(
    isMuted: Boolean,
    isCameraOn: Boolean,
    isScreenSharing: Boolean,
    outputRoute: AudioOutputRoute,
    showAudioRouteMenu: Boolean,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onOpenAudioMenu: () -> Unit,
    onDismissAudioMenu: () -> Unit,
    onSelectAudioRoute: (AudioOutputRoute) -> Unit,
    onHangUp: () -> Unit
) {
    Surface(
        color = Color(0xFF1B1D24),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallRoundControl(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (isMuted) "Unmute" else "Mute",
                activeColor = if (isMuted) Color(0xFFEF4444) else Color(0xFF2B2F3A),
                testTag = "control_mute",
                onClick = onToggleMute
            )
            CallRoundControl(
                icon = if (isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = if (isCameraOn) "Cam On" else "Cam Off",
                activeColor = if (isCameraOn) Color(0xFF2B2F3A) else Color(0xFF4B5563),
                testTag = "control_camera",
                onClick = onToggleCamera
            )
            CallRoundControl(
                icon = Icons.Default.Cameraswitch,
                label = "Flip",
                activeColor = Color(0xFF2B2F3A),
                testTag = "control_flip_camera",
                onClick = onSwitchCamera
            )
            CallRoundControl(
                icon = if (isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                label = if (isScreenSharing) "Stop" else "Share",
                activeColor = if (isScreenSharing) Color(0xFF7C3AED) else Color(0xFF2B2F3A),
                testTag = "control_screen_share",
                onClick = onToggleScreenShare
            )
            Box {
                CallRoundControl(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = outputRoute.label,
                    activeColor = Color(0xFF2B2F3A),
                    testTag = "control_audio_route",
                    onClick = onOpenAudioMenu
                )
                DropdownMenu(
                    expanded = showAudioRouteMenu,
                    onDismissRequest = onDismissAudioMenu
                ) {
                    AudioOutputRoute.entries.forEach { route ->
                        DropdownMenuItem(
                            text = { Text(route.label) },
                            onClick = { onSelectAudioRoute(route) }
                        )
                    }
                }
            }
            CallRoundControl(
                icon = Icons.Default.CallEnd,
                label = "End",
                activeColor = Color(0xFFDC2626),
                testTag = "control_hang_up",
                onClick = onHangUp
            )
        }
    }
}

@Composable
private fun CallRoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    activeColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(activeColor)
                .testTag(testTag)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFF9CA3AF), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun WebRtcVideoRenderer(
    videoTrack: VideoTrack,
    eglContext: EglBase.Context,
    isScreenShare: Boolean,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    val currentTrack by rememberUpdatedState(videoTrack)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(
                    if (isScreenShare) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
                setEnableHardwareScaler(true)
                setMirror(mirror)
                currentTrack.addSink(this)
            }
        },
        update = { view ->
            view.setMirror(mirror)
        },
        onRelease = { view ->
            try {
                currentTrack.removeSink(view)
            } catch (_: Exception) {
            }
            view.release()
        }
    )
}
