package com.dezacodex.call

import android.content.Context
import android.content.Intent
import com.dezacodex.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates Discord-style multi-stream video conferencing:
 * - Independent local webcam + MediaProjection screen sharing + remote participant streams
 * - Real AudioRecord + WebRTC audioLevel VAD & ActiveSpeakerManager
 * - Grid View & Focus View layout switching without call interruption
 * - Participant pinning & smart PiP / compact camera selection
 * - Device enumeration & live camera/mic/speaker switching
 * - WebSocket signaling + simulcast/bandwidth adaptation + full track cleanup
 */
class DiscordCallController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val webRtcEngine = WebRtcCallEngine(context)
    private val signalingClient = WebSocketSignalingClient()
    private val localVad = VoiceActivityDetector()
    private val localAudioMonitor = LocalAudioLevelMonitor(localVad)
    private val activeSpeakerManager = ActiveSpeakerManager()
    private val remoteVads = mutableMapOf<String, VoiceActivityDetector>()

    private val _uiState = MutableStateFlow(
        CallUiState(
            signalingServerUrl = BuildConfig.SIGNALING_WS_URL
                .takeIf { it.startsWith("ws://") || it.startsWith("wss://") } ?: ""
        )
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var statsJob: Job? = null
    private var signalingEventsJob: Job? = null
    private var localParticipantId: String = "local_user"
    private var localDisplayName: String = "You"

    init {
        wireWebRtcCallbacks()
        wireSignalingObservers()
        refreshDevices()
    }

    private fun wireWebRtcCallbacks() {
        webRtcEngine.onIceCandidateGenerated = { peerId, sdpMid, sdpMLineIndex, candidate ->
            signalingClient.sendIceCandidate(peerId, sdpMid, sdpMLineIndex, candidate)
        }
        webRtcEngine.onRemoteTrackAdded = { peerId, trackId, isScreenShare ->
            updateParticipant(peerId) { existing ->
                if (isScreenShare) {
                    existing.copy(screenTrackId = trackId, isScreenSharing = true)
                } else {
                    existing.copy(webcamTrackId = trackId, isWebcamEnabled = true)
                }
            }
            if (isScreenShare) {
                _uiState.update {
                    it.copy(
                        layoutMode = CallLayoutMode.FOCUS,
                        focusTarget = FocusTarget.SCREEN_SHARE,
                        focusedParticipantId = peerId
                    )
                }
            }
        }
        webRtcEngine.onRemoteTrackRemoved = { peerId, trackId ->
            updateParticipant(peerId) { existing ->
                if (existing.screenTrackId == trackId) {
                    existing.copy(screenTrackId = null, isScreenSharing = false)
                } else if (existing.webcamTrackId == trackId) {
                    existing.copy(webcamTrackId = null, isWebcamEnabled = false)
                } else {
                    existing
                }
            }
        }
        webRtcEngine.onRemoteAudioLevel = { peerId, level ->
            handleRemoteAudioLevel(peerId, level)
        }
        webRtcEngine.onScreenShareStoppedBySystem = {
            stopScreenShareInternal(notifyService = true)
        }
        webRtcEngine.onCameraDisconnected = { msg ->
            _uiState.update {
                it.copy(
                    localCamEnabled = false,
                    callError = "Camera device disconnected ($msg). Call audio remains active."
                )
            }
            updateParticipant(localParticipantId) { it.copy(isWebcamEnabled = false) }
        }
    }

    private fun wireSignalingObservers() {
        signalingEventsJob?.cancel()
        signalingEventsJob = scope.launch {
            launch {
                signalingClient.connectionState.collect { connState ->
                    _uiState.update { it.copy(signalingState = connState) }
                }
            }
            launch {
                signalingClient.events.collect { event ->
                    when (event) {
                        is SignalingEvent.PeerJoined -> {
                            ensureRemoteParticipant(event.peerId, event.displayName)
                            webRtcEngine.createOffer(event.peerId) { sdp ->
                                signalingClient.sendOffer(event.peerId, sdp)
                            }
                        }
                        is SignalingEvent.PeerLeft -> {
                            webRtcEngine.removePeer(event.peerId)
                            remoteVads.remove(event.peerId)
                            _uiState.update { state ->
                                val remaining = state.participants.filterNot { it.participantId == event.peerId }
                                val newPin = if (state.pinnedParticipantId == event.peerId) null else state.pinnedParticipantId
                                val newFocus = if (state.focusedParticipantId == event.peerId) null else state.focusedParticipantId
                                val newSpeaker = if (state.activeSpeakerId == event.peerId) null else state.activeSpeakerId
                                state.copy(
                                    participants = remaining,
                                    pinnedParticipantId = newPin,
                                    focusedParticipantId = newFocus,
                                    activeSpeakerId = newSpeaker
                                )
                            }
                        }
                        is SignalingEvent.OfferReceived -> {
                            ensureRemoteParticipant(event.fromPeerId, event.fromPeerId)
                            webRtcEngine.handleOffer(event.fromPeerId, event.sdp) { answer ->
                                signalingClient.sendAnswer(event.fromPeerId, answer)
                            }
                        }
                        is SignalingEvent.AnswerReceived -> {
                            webRtcEngine.handleAnswer(event.fromPeerId, event.sdp)
                        }
                        is SignalingEvent.IceCandidateReceived -> {
                            webRtcEngine.addIceCandidate(
                                event.fromPeerId,
                                event.sdpMid,
                                event.sdpMLineIndex,
                                event.candidate
                            )
                        }
                        is SignalingEvent.MediaStateUpdated -> {
                            ensureRemoteParticipant(event.fromPeerId, event.fromPeerId)
                            updateParticipant(event.fromPeerId) { p ->
                                p.copy(
                                    isMicMuted = event.micMuted,
                                    isWebcamEnabled = event.camEnabled,
                                    isScreenSharing = event.screenSharing
                                )
                            }
                            handleRemoteAudioLevel(event.fromPeerId, event.audioLevel)
                        }
                        is SignalingEvent.Error -> {
                            _uiState.update { it.copy(callError = event.message) }
                        }
                    }
                }
            }
        }
    }

    fun refreshDevices() {
        val cameras = webRtcEngine.enumerateCameras()
        val outputs = webRtcEngine.enumerateAudioOutputs()
        val mics = listOf(
            MicrophoneDeviceOption("default_comm", "Phone Communication Mic"),
            MicrophoneDeviceOption("unprocessed", "Raw Studio Mic")
        )
        _uiState.update { state ->
            state.copy(
                availableCameras = cameras,
                selectedCameraId = state.selectedCameraId ?: cameras.firstOrNull { it.isFrontFacing }?.deviceId ?: cameras.firstOrNull()?.deviceId,
                availableMicrophones = mics,
                selectedMicId = state.selectedMicId ?: mics.first().deviceId,
                availableAudioOutputs = outputs
            )
        }
    }

    fun updatePermissions(hasCamera: Boolean, hasMic: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = hasCamera, hasMicPermission = hasMic) }
    }

    fun startOutgoingCall(
        callType: String,
        roomId: String,
        localUserEmail: String,
        localUserName: String,
        partnerDisplayName: String?,
        signalingUrl: String = _uiState.value.signalingServerUrl
    ) {
        localParticipantId = localUserEmail.ifBlank { "local_user" }
        localDisplayName = localUserName.ifBlank { "You" }
        val camOn = callType == "VIDEO" && _uiState.value.hasCameraPermission
        val localParticipant = ParticipantStream(
            participantId = localParticipantId,
            displayName = localDisplayName,
            isLocal = true,
            webcamTrackId = null,
            isWebcamEnabled = camOn,
            isMicMuted = false
        )
        val initialParticipants = mutableListOf(localParticipant)
        if (!partnerDisplayName.isNullOrBlank()) {
            initialParticipants.add(
                ParticipantStream(
                    participantId = "partner_peer",
                    displayName = partnerDisplayName,
                    isLocal = false,
                    isWebcamEnabled = false,
                    isMicMuted = false
                )
            )
        }

        _uiState.update {
            it.copy(
                callStatus = "OUTGOING",
                callType = callType,
                roomId = roomId,
                localCamEnabled = camOn,
                localMicMuted = false,
                isScreenSharing = false,
                layoutMode = CallLayoutMode.GRID,
                participants = initialParticipants,
                callError = null,
                elapsedSeconds = 0L,
                signalingServerUrl = signalingUrl
            )
        }

        scope.launch {
            delay(1400)
            if (_uiState.value.callStatus == "OUTGOING") {
                activateConnectedCallSession(roomId, signalingUrl)
            }
        }
    }

    fun receiveIncomingCall(
        callType: String,
        roomId: String,
        localUserEmail: String,
        localUserName: String,
        callerDisplayName: String
    ) {
        localParticipantId = localUserEmail.ifBlank { "local_user" }
        localDisplayName = localUserName.ifBlank { "You" }
        _uiState.update {
            it.copy(
                callStatus = "INCOMING",
                callType = callType,
                roomId = roomId,
                participants = listOf(
                    ParticipantStream(localParticipantId, localDisplayName, isLocal = true),
                    ParticipantStream("partner_peer", callerDisplayName, isLocal = false)
                ),
                callError = null
            )
        }
    }

    fun acceptIncomingCall() {
        val state = _uiState.value
        if (state.callStatus != "INCOMING") return
        activateConnectedCallSession(state.roomId, state.signalingServerUrl)
    }

    private fun activateConnectedCallSession(roomId: String, signalingUrl: String) {
        val state = _uiState.value
        val startCam = state.callType == "VIDEO" && state.hasCameraPermission
        val micAllowed = state.hasMicPermission

        if (micAllowed) {
            webRtcEngine.startLocalAudio(muted = state.localMicMuted)
            startLocalVadMonitor()
        } else {
            _uiState.update {
                it.copy(
                    localMicMuted = true,
                    callError = "Microphone permission not granted. Audio input muted."
                )
            }
        }

        var localCamTrackId: String? = null
        if (startCam) {
            localCamTrackId = webRtcEngine.startLocalWebcam(
                preferredCameraId = state.selectedCameraId,
                preset = state.qualityPreset
            )
        } else if (state.callType == "VIDEO" && !state.hasCameraPermission) {
            _uiState.update {
                it.copy(
                    localCamEnabled = false,
                    callError = "Camera permission denied — continuing in audio/avatar mode."
                )
            }
        }

        webRtcEngine.setAudioOutputDevice(state.selectedAudioOutput)
        runCatching { MediaProjectionService.startCallForeground(context, includeScreenProjection = false) }

        _uiState.update {
            it.copy(
                callStatus = "CONNECTED",
                localCamEnabled = startCam,
                participants = it.participants.map { p ->
                    if (p.isLocal) {
                        p.copy(
                            webcamTrackId = localCamTrackId,
                            isWebcamEnabled = startCam,
                            isMicMuted = !micAllowed || state.localMicMuted
                        )
                    } else p
                }
            )
        }

        if (signalingUrl.isNotBlank()) {
            signalingClient.connect(
                serverUrl = signalingUrl,
                roomId = roomId,
                peerId = localParticipantId,
                displayName = localDisplayName,
                scope = scope
            )
        }

        startCallTimers()
    }

    private fun startLocalVadMonitor() {
        localAudioMonitor.updateSensitivity(_uiState.value.vadSensitivity)
        localAudioMonitor.start(
            scope = scope,
            onLevel = { vadResult ->
                val muted = _uiState.value.localMicMuted
                val speaking = !muted && vadResult.isSpeaking
                val rms = if (muted) 0f else vadResult.rmsNormalized
                val db = if (muted) -90f else vadResult.levelDb

                _uiState.update { state ->
                    val updatedParticipants = state.participants.map { p ->
                        if (p.isLocal) {
                            p.copy(
                                isMicMuted = muted,
                                audioLevel = rms,
                                audioDb = db,
                                isSpeaking = speaking
                            )
                        } else p
                    }
                    val resolvedSpeaker = activeSpeakerManager.resolveActiveSpeaker(updatedParticipants)
                    state.copy(
                        participants = updatedParticipants,
                        activeSpeakerId = resolvedSpeaker
                    )
                }
            },
            onUnavailable = { /* Graceful fallback to WebRTC stats / mute indicator */ }
        )
    }

    internal fun handleRemoteAudioLevel(peerId: String, level: Float) {
        val vad = remoteVads.getOrPut(peerId) { VoiceActivityDetector(_uiState.value.vadSensitivity) }
        val result = vad.processNormalizedLevel(level)
        _uiState.update { state ->
            val updated = state.participants.map { p ->
                if (p.participantId == peerId) {
                    val speaking = !p.isMicMuted && result.isSpeaking
                    p.copy(
                        audioLevel = if (p.isMicMuted) 0f else result.rmsNormalized,
                        audioDb = if (p.isMicMuted) -90f else result.levelDb,
                        isSpeaking = speaking
                    )
                } else p
            }
            val resolvedSpeaker = activeSpeakerManager.resolveActiveSpeaker(updated)
            state.copy(
                participants = updated,
                activeSpeakerId = resolvedSpeaker
            )
        }
    }

    private fun startCallTimers() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && _uiState.value.callStatus == "CONNECTED") {
                delay(1000)
                _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1L) }
            }
        }
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && _uiState.value.callStatus == "CONNECTED") {
                delay(2000)
                webRtcEngine.collectNetworkStats { snapshot ->
                    _uiState.update { it.copy(networkStats = snapshot) }
                }
            }
        }
    }

    fun toggleMicrophone() {
        val nextMuted = !_uiState.value.localMicMuted
        if (!nextMuted && !_uiState.value.hasMicPermission) {
            _uiState.update { it.copy(callError = "Microphone permission is required to unmute.") }
            return
        }
        webRtcEngine.setLocalAudioMuted(nextMuted)
        _uiState.update { state ->
            val updated = state.participants.map { p ->
                if (p.isLocal) p.copy(isMicMuted = nextMuted, isSpeaking = if (nextMuted) false else p.isSpeaking, audioLevel = if (nextMuted) 0f else p.audioLevel)
                else p
            }
            state.copy(
                localMicMuted = nextMuted,
                participants = updated,
                activeSpeakerId = activeSpeakerManager.resolveActiveSpeaker(updated)
            )
        }
        broadcastLocalMediaState()
    }

    fun toggleCamera() {
        val nextCam = !_uiState.value.localCamEnabled
        if (nextCam && !_uiState.value.hasCameraPermission) {
            _uiState.update { it.copy(callError = "Camera permission is required to enable video.") }
            return
        }
        val trackId = if (nextCam) {
            webRtcEngine.startLocalWebcam(_uiState.value.selectedCameraId, _uiState.value.qualityPreset) ?: "local_webcam"
        } else {
            webRtcEngine.setLocalWebcamEnabled(false)
            _uiState.value.participants.find { it.isLocal }?.webcamTrackId
        }
        _uiState.update { state ->
            state.copy(
                localCamEnabled = nextCam,
                participants = state.participants.map { p ->
                    if (p.isLocal) p.copy(isWebcamEnabled = nextCam, webcamTrackId = trackId)
                    else p
                }
            )
        }
        broadcastLocalMediaState()
    }

    fun startScreenShare(permissionResultData: Intent, captureAudio: Boolean = false) {
        runCatching { MediaProjectionService.startCallForeground(context, includeScreenProjection = true) }
        val screenTrackId = webRtcEngine.startScreenShare(permissionResultData) ?: "local_screen"
        _uiState.update { state ->
            state.copy(
                isScreenSharing = true,
                screenShareAudioEnabled = captureAudio,
                layoutMode = CallLayoutMode.FOCUS,
                focusTarget = FocusTarget.SCREEN_SHARE,
                focusedParticipantId = localParticipantId,
                participants = state.participants.map { p ->
                    if (p.isLocal) {
                        // Crucial: webcam track remains completely untouched!
                        p.copy(isScreenSharing = true, screenTrackId = screenTrackId)
                    } else p
                }
            )
        }
        broadcastLocalMediaState()
    }

    fun stopScreenShare() {
        stopScreenShareInternal(notifyService = true)
    }

    private fun stopScreenShareInternal(notifyService: Boolean) {
        webRtcEngine.stopScreenShare()
        if (notifyService && _uiState.value.callStatus == "CONNECTED") {
            runCatching { MediaProjectionService.startCallForeground(context, includeScreenProjection = false) }
        }
        _uiState.update { state ->
            val updated = state.participants.map { p ->
                if (p.isLocal) p.copy(isScreenSharing = false, screenTrackId = null) else p
            }
            val anyRemoteScreen = updated.any { it.isScreenSharing }
            state.copy(
                isScreenSharing = false,
                screenShareAudioEnabled = false,
                layoutMode = if (anyRemoteScreen) state.layoutMode else CallLayoutMode.GRID,
                participants = updated
            )
        }
        broadcastLocalMediaState()
    }

    fun switchCamera(cameraId: String? = null) {
        webRtcEngine.switchCamera(cameraId) { newId ->
            _uiState.update { state ->
                val resolved = newId ?: state.availableCameras.firstOrNull { it.deviceId != state.selectedCameraId }?.deviceId
                state.copy(selectedCameraId = resolved)
            }
        }
    }

    fun selectMicrophone(micId: String) {
        _uiState.update { it.copy(selectedMicId = micId) }
    }

    fun selectAudioOutput(output: AudioOutputDevice) {
        webRtcEngine.setAudioOutputDevice(output)
        _uiState.update { it.copy(selectedAudioOutput = output) }
    }

    fun setLayoutMode(mode: CallLayoutMode) {
        _uiState.update { it.copy(layoutMode = mode) }
    }

    fun setFocusTarget(target: FocusTarget, participantId: String? = null) {
        _uiState.update {
            it.copy(
                layoutMode = CallLayoutMode.FOCUS,
                focusTarget = target,
                focusedParticipantId = participantId ?: it.focusedParticipantId
            )
        }
    }

    fun togglePinParticipant(participantId: String) {
        _uiState.update { state ->
            val nextPin = if (state.pinnedParticipantId == participantId) null else participantId
            state.copy(
                pinnedParticipantId = nextPin,
                participants = state.participants.map { p ->
                    p.copy(isPinned = (p.participantId == nextPin))
                }
            )
        }
    }

    fun toggleHideNonVideoParticipants() {
        _uiState.update { it.copy(hideNonVideoParticipants = !it.hideNonVideoParticipants) }
    }

    fun setVadSensitivity(sensitivity: VadSensitivity) {
        localVad.setSensitivity(sensitivity)
        remoteVads.values.forEach { it.setSensitivity(sensitivity) }
        _uiState.update { it.copy(vadSensitivity = sensitivity) }
    }

    fun setVideoQualityPreset(preset: VideoQualityPreset) {
        webRtcEngine.applyQualityPreset(preset)
        _uiState.update {
            it.copy(
                qualityPreset = preset,
                networkStats = it.networkStats.copy(
                    activeSimulcastLayer = preset.name.lowercase(),
                    degradationPreference = when (preset) {
                        VideoQualityPreset.HIGH -> "MAINTAIN_RESOLUTION"
                        VideoQualityPreset.LOW -> "MAINTAIN_FRAMERATE"
                        else -> "BALANCED"
                    }
                )
            )
        }
    }

    fun setPipMode(inPip: Boolean) {
        _uiState.update { it.copy(isInPipMode = inPip) }
    }

    fun setMinimizedCallWindow(minimized: Boolean) {
        _uiState.update { it.copy(isMinimizedCallWindow = minimized) }
    }

    fun setShowParticipantsSheet(show: Boolean) {
        _uiState.update { it.copy(showParticipantsSheet = show) }
    }

    fun setShowDeviceSettingsSheet(show: Boolean) {
        _uiState.update { it.copy(showDeviceSettingsSheet = show) }
    }

    fun updateSignalingServerUrl(url: String) {
        _uiState.update { it.copy(signalingServerUrl = url.trim()) }
    }

    fun clearCallError() {
        _uiState.update { it.copy(callError = null) }
    }

    /**
     * Used by smart PiP / compact views to get the current spotlight participant without flickering.
     */
    fun getSpotlightParticipant(): ParticipantStream? {
        val state = _uiState.value
        return activeSpeakerManager.selectSpotlightParticipant(
            participants = state.participants,
            pinnedParticipantId = state.pinnedParticipantId,
            activeSpeakerId = state.activeSpeakerId
        )
    }

    /**
     * Adds or updates a remote participant stream (e.g., from WebRTC signaling or multi-participant room sync).
     */
    fun upsertParticipant(stream: ParticipantStream) {
        _uiState.update { state ->
            val exists = state.participants.any { it.participantId == stream.participantId }
            val next = if (exists) {
                state.participants.map { if (it.participantId == stream.participantId) stream else it }
            } else {
                state.participants + stream
            }
            state.copy(participants = next)
        }
    }

    fun removeParticipant(participantId: String) {
        webRtcEngine.removePeer(participantId)
        remoteVads.remove(participantId)
        _uiState.update { state ->
            state.copy(
                participants = state.participants.filterNot { it.participantId == participantId },
                pinnedParticipantId = if (state.pinnedParticipantId == participantId) null else state.pinnedParticipantId,
                activeSpeakerId = if (state.activeSpeakerId == participantId) null else state.activeSpeakerId
            )
        }
    }

    fun hangUpCall() {
        timerJob?.cancel()
        timerJob = null
        statsJob?.cancel()
        statsJob = null
        localAudioMonitor.stop()
        activeSpeakerManager.reset()
        remoteVads.clear()
        signalingClient.disconnect()
        webRtcEngine.disposeAllMedia()
        runCatching { MediaProjectionService.stopForeground(context) }

        _uiState.update {
            it.copy(
                callStatus = "IDLE",
                isScreenSharing = false,
                screenShareAudioEnabled = false,
                isInPipMode = false,
                isMinimizedCallWindow = false,
                showParticipantsSheet = false,
                showDeviceSettingsSheet = false,
                pinnedParticipantId = null,
                activeSpeakerId = null,
                focusedParticipantId = null,
                elapsedSeconds = 0L,
                participants = emptyList(),
                callError = null
            )
        }
    }

    private fun ensureRemoteParticipant(peerId: String, displayName: String) {
        if (_uiState.value.participants.none { it.participantId == peerId }) {
            _uiState.update { state ->
                state.copy(
                    participants = state.participants + ParticipantStream(
                        participantId = peerId,
                        displayName = displayName,
                        isLocal = false
                    )
                )
            }
        }
    }

    private fun updateParticipant(peerId: String, transform: (ParticipantStream) -> ParticipantStream) {
        _uiState.update { state ->
            state.copy(
                participants = state.participants.map { p ->
                    if (p.participantId == peerId) transform(p) else p
                }
            )
        }
    }

    private fun broadcastLocalMediaState() {
        val state = _uiState.value
        val local = state.participants.find { it.isLocal }
        signalingClient.sendMediaState(
            micMuted = state.localMicMuted,
            camEnabled = state.localCamEnabled,
            screenSharing = state.isScreenSharing,
            webcamStreamId = local?.webcamTrackId,
            screenStreamId = local?.screenTrackId,
            audioLevel = local?.audioLevel ?: 0f
        )
    }
}
