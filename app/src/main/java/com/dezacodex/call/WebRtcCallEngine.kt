package com.dezacodex.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import com.dezacodex.BuildConfig
import com.dezacodex.data.FirestoreCallParticipant
import com.dezacodex.data.FirestoreCallSignal
import com.dezacodex.data.FirestoreHeartRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

enum class CallLayoutMode { GRID, FOCUS }

enum class SimulcastTier(val label: String, val maxBitrateKbps: Int, val scaleDownBy: Double) {
    HIGH("HD 720p", 1500, 1.0),
    MEDIUM("SD 480p", 550, 2.0),
    LOW("LD 240p", 160, 4.0)
}

data class ParticipantMediaState(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val isLocal: Boolean = false,
    val isAudioMuted: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val isScreenSharing: Boolean = false,
    val screenAudioEnabled: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioLevelDb: Double = -90.0,
    val hasCameraTrack: Boolean = false,
    val hasScreenTrack: Boolean = false,
    val videoQuality: String = "simulcast"
)

data class NetworkStatsSnapshot(
    val isMetered: Boolean = false,
    val downKbpsEstimate: Int = 2500,
    val activeSimulcastTier: SimulcastTier = SimulcastTier.HIGH,
    val iceConnectionState: String = "NEW",
    val activeTracksCount: Int = 0
)

data class DiscordCallUiState(
    val callRoomId: String? = null,
    val callType: String = "VIDEO",
    val status: String = "IDLE", // IDLE, OUTGOING, INCOMING, CONNECTED, RECONNECTING
    val layoutMode: CallLayoutMode = CallLayoutMode.GRID,
    val pinnedUid: String? = null,
    val activeSpeakerUid: String? = null,
    val lastActiveVideoUid: String? = null,
    val autoCamSwitchEnabled: Boolean = true,
    val isCompactPipMode: Boolean = false,
    val hideNonVideoParticipants: Boolean = false,
    val isUsingFrontCamera: Boolean = true,
    val isScreenSharing: Boolean = false,
    val screenAudioEnabled: Boolean = false,
    val outputRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER,
    val participants: List<ParticipantMediaState> = emptyList(),
    val stats: NetworkStatsSnapshot = NetworkStatsSnapshot(),
    val permissionWarning: String? = null,
    val nativeWebRtcReady: Boolean = false
)

/**
 * Discord-style multi-stream WebRTC call controller.
 * Keeps camera track and screen-share track completely independent so sharing a screen
 * never replaces the user's webcam feed.
 */
class WebRtcCallEngine(
    private val context: Context,
    private val repositoryProvider: () -> FirestoreHeartRepository?,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow(DiscordCallUiState())
    val uiState: StateFlow<DiscordCallUiState> = _uiState.asStateFlow()

    val vad = VoiceActivityDetector(context)
    private val audioRouting = AudioRoutingManager(context)

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var localCameraSource: VideoSource? = null
    private var localCameraCapturer: CameraVideoCapturer? = null
    private var localCameraHelper: SurfaceTextureHelper? = null
    private var localCameraTrack: VideoTrack? = null

    private var localScreenSource: VideoSource? = null
    private var localScreenCapturer: ScreenCapturerAndroid? = null
    private var localScreenHelper: SurfaceTextureHelper? = null
    private var localScreenTrack: VideoTrack? = null

    private val remoteCameraTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteCameras: StateFlow<Map<String, VideoTrack>> = remoteCameraTracks.asStateFlow()

    private val remoteScreenTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteScreens: StateFlow<Map<String, VideoTrack>> = remoteScreenTracks.asStateFlow()

    private val localCameraFlow = MutableStateFlow<VideoTrack?>(null)
    val localCamera: StateFlow<VideoTrack?> = localCameraFlow.asStateFlow()

    private val localScreenFlow = MutableStateFlow<VideoTrack?>(null)
    val localScreen: StateFlow<VideoTrack?> = localScreenFlow.asStateFlow()

    private val processedSignalIds = mutableSetOf<String>()
    private var signalingJob: Job? = null
    private var participantsJob: Job? = null
    private var vadSyncJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var localUid: String = ""
    private var localDisplayName: String = "You"
    private var roomMembers: List<String> = emptyList()

    val sharedEglContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    private fun ensureWebRtcInitialized(): Boolean {
        if (peerConnectionFactory != null) return true
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val egl = EglBase.create()
            eglBase = egl
            val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            _uiState.value = _uiState.value.copy(nativeWebRtcReady = true)
            true
        } catch (t: Throwable) {
            // Headless JVM / Robolectric environment has no native libjingle_peerconnection_so.so
            Log.w(TAG, "Native WebRTC library unavailable in current host environment: ${t.message}")
            _uiState.value = _uiState.value.copy(nativeWebRtcReady = false)
            false
        }
    }

    fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val turnUrl = BuildConfig.TURN_URL.trim()
        if (turnUrl.isNotEmpty() && turnUrl != "UNCONFIGURED" &&
            (turnUrl.startsWith("turn:") || turnUrl.startsWith("turns:"))
        ) {
            servers.add(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(BuildConfig.TURN_USER)
                    .setPassword(BuildConfig.TURN_PASS)
                    .createIceServer()
            )
        }
        return servers
    }

    fun buildSimulcastEncodings(): List<RtpParameters.Encoding> {
        return listOf(
            RtpParameters.Encoding("q", true, SimulcastTier.LOW.scaleDownBy).apply {
                maxBitrateBps = SimulcastTier.LOW.maxBitrateKbps * 1000
                maxFramerate = 15
            },
            RtpParameters.Encoding("h", true, SimulcastTier.MEDIUM.scaleDownBy).apply {
                maxBitrateBps = SimulcastTier.MEDIUM.maxBitrateKbps * 1000
                maxFramerate = 24
            },
            RtpParameters.Encoding("f", true, SimulcastTier.HIGH.scaleDownBy).apply {
                maxBitrateBps = SimulcastTier.HIGH.maxBitrateKbps * 1000
                maxFramerate = 30
            }
        )
    }

    fun startCallSession(
        callRoomId: String,
        myUid: String,
        myDisplayName: String,
        partnerUid: String?,
        partnerDisplayName: String?,
        callType: String
    ) {
        localUid = myUid
        localDisplayName = myDisplayName
        roomMembers = listOfNotNull(myUid, partnerUid).distinct()

        val initialParticipants = mutableListOf(
            ParticipantMediaState(
                uid = myUid,
                displayName = myDisplayName,
                isLocal = true,
                isCameraEnabled = (callType == "VIDEO"),
                isAudioMuted = false
            )
        )
        if (!partnerUid.isNullOrBlank()) {
            initialParticipants.add(
                ParticipantMediaState(
                    uid = partnerUid,
                    displayName = partnerDisplayName ?: "Partner",
                    isLocal = false,
                    isCameraEnabled = (callType == "VIDEO"),
                    isAudioMuted = false
                )
            )
        }

        _uiState.value = DiscordCallUiState(
            callRoomId = callRoomId,
            callType = callType,
            status = "CONNECTED",
            layoutMode = CallLayoutMode.GRID,
            lastActiveVideoUid = partnerUid ?: myUid,
            participants = initialParticipants
        )

        audioRouting.configureCallAudio(
            if (callType == "VIDEO") AudioOutputRoute.SPEAKER else AudioOutputRoute.EARPIECE
        )
        _uiState.value = _uiState.value.copy(
            outputRoute = if (callType == "VIDEO") AudioOutputRoute.SPEAKER else AudioOutputRoute.EARPIECE
        )

        val initialized = ensureWebRtcInitialized()
        if (initialized) {
            setupLocalAudioTrack()
            if (callType == "VIDEO") {
                setupLocalCameraTrack(front = true)
            }
            partnerUid?.let { peerUid ->
                getOrCreatePeerConnection(peerUid, initiateOffer = (myUid <= peerUid))
            }
        }

        startCallForegroundService(withScreenShare = false)
        startNetworkBandwidthMonitor()
        startVadMonitoring()
        subscribeToFirestoreSignaling(callRoomId)
        syncLocalParticipantState()
    }

    private fun startCallForegroundService(withScreenShare: Boolean) {
        try {
            CallMediaService.start(context, withScreenShare)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground CallMediaService: ${e.message}")
        }
    }

    private fun setupLocalAudioTrack() {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            setPermissionWarning("Microphone permission denied. Call joined in listen-only mode.")
            updateLocalParticipant { it.copy(isAudioMuted = true) }
            return
        }
        val factory = peerConnectionFactory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio_$localUid", localAudioSource).apply {
            setEnabled(true)
        }
    }

    private fun setupLocalCameraTrack(front: Boolean) {
        val hasCam = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            setPermissionWarning("Camera permission denied. Audio & screen sharing remain available.")
            updateLocalParticipant { it.copy(isCameraEnabled = false, hasCameraTrack = false) }
            return
        }
        val factory = peerConnectionFactory ?: return
        val egl = eglBase ?: return

        try {
            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { name ->
                if (front) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
            } ?: enumerator.deviceNames.firstOrNull()

            if (deviceName == null) {
                setPermissionWarning("No hardware camera detected on this device.")
                updateLocalParticipant { it.copy(isCameraEnabled = false, hasCameraTrack = false) }
                return
            }

            val capturer = enumerator.createCapturer(deviceName, null)
            localCameraCapturer = capturer
            val helper = SurfaceTextureHelper.create("CameraCaptureThread", egl.eglBaseContext)
            localCameraHelper = helper
            val source = factory.createVideoSource(false)
            localCameraSource = source
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(1280, 720, 30)

            val track = factory.createVideoTrack("camera_$localUid", source).apply {
                setEnabled(true)
            }
            localCameraTrack = track
            localCameraFlow.value = track
            updateLocalParticipant { it.copy(isCameraEnabled = true, hasCameraTrack = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Camera initialization failed: ${e.message}")
            setPermissionWarning("Camera hardware unavailable or disconnected.")
            updateLocalParticipant { it.copy(isCameraEnabled = false, hasCameraTrack = false) }
        }
    }

    /**
     * Starts an independent screen-sharing track using MediaProjection consent Intent.
     * Crucially, this does NOT stop or replace localCameraTrack!
     */
    fun startScreenSharing(mediaProjectionPermissionData: Intent, shareScreenAudio: Boolean = true) {
        _uiState.value = _uiState.value.copy(
            isScreenSharing = true,
            screenAudioEnabled = shareScreenAudio,
            layoutMode = CallLayoutMode.FOCUS
        )
        updateLocalParticipant {
            it.copy(
                isScreenSharing = true,
                screenAudioEnabled = shareScreenAudio,
                hasScreenTrack = true
            )
        }
        startCallForegroundService(withScreenShare = true)

        val factory = peerConnectionFactory
        val egl = eglBase
        if (factory != null && egl != null) {
            try {
                val capturer = ScreenCapturerAndroid(
                    mediaProjectionPermissionData,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopScreenSharing()
                        }
                    }
                )
                localScreenCapturer = capturer
                val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext)
                localScreenHelper = helper
                val source = factory.createVideoSource(true)
                localScreenSource = source
                capturer.initialize(helper, context, source.capturerObserver)
                capturer.startCapture(1280, 720, 15)

                val track = factory.createVideoTrack("screen_$localUid", source).apply {
                    setEnabled(true)
                }
                localScreenTrack = track
                localScreenFlow.value = track

                peerConnections.forEach { (peerUid, pc) ->
                    attachTrackWithSimulcast(pc, track, "screen_$localUid")
                    createAndSendOffer(pc, peerUid, "SCREEN")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screen share start failed: ${e.message}")
                setPermissionWarning("Screen share unavailable: ${e.localizedMessage}")
            }
        }
        syncLocalParticipantState()
    }

    fun stopScreenSharing() {
        try {
            localScreenCapturer?.stopCapture()
            localScreenCapturer?.dispose()
        } catch (_: Exception) {
        }
        localScreenCapturer = null
        localScreenTrack?.setEnabled(false)
        localScreenTrack?.dispose()
        localScreenTrack = null
        localScreenFlow.value = null
        localScreenSource?.dispose()
        localScreenSource = null
        localScreenHelper?.dispose()
        localScreenHelper = null

        _uiState.value = _uiState.value.copy(
            isScreenSharing = false,
            screenAudioEnabled = false
        )
        updateLocalParticipant {
            it.copy(
                isScreenSharing = false,
                screenAudioEnabled = false,
                hasScreenTrack = false
            )
        }
        startCallForegroundService(withScreenShare = false)
        syncLocalParticipantState()
    }

    fun toggleMute(): Boolean {
        val current = _uiState.value.participants.firstOrNull { it.isLocal }?.isAudioMuted ?: false
        val next = !current
        localAudioTrack?.setEnabled(!next)
        updateLocalParticipant {
            it.copy(isAudioMuted = next, isSpeaking = if (next) false else it.isSpeaking)
        }
        syncLocalParticipantState()
        return next
    }

    fun toggleCamera(): Boolean {
        val current = _uiState.value.participants.firstOrNull { it.isLocal }?.isCameraEnabled ?: false
        val next = !current
        if (next && localCameraTrack == null && peerConnectionFactory != null) {
            setupLocalCameraTrack(_uiState.value.isUsingFrontCamera)
        }
        localCameraTrack?.setEnabled(next)
        updateLocalParticipant {
            it.copy(isCameraEnabled = next, hasCameraTrack = next)
        }
        syncLocalParticipantState()
        return next
    }

    fun switchCamera() {
        val nextFront = !_uiState.value.isUsingFrontCamera
        _uiState.value = _uiState.value.copy(isUsingFrontCamera = nextFront)
        localCameraCapturer?.switchCamera(null)
    }

    fun toggleLayoutMode() {
        val next = if (_uiState.value.layoutMode == CallLayoutMode.GRID) {
            CallLayoutMode.FOCUS
        } else {
            CallLayoutMode.GRID
        }
        _uiState.value = _uiState.value.copy(layoutMode = next)
    }

    fun setLayoutMode(mode: CallLayoutMode) {
        _uiState.value = _uiState.value.copy(layoutMode = mode)
    }

    /**
     * Pins/unpins a participant. Pinning explicitly overrides automatic speaker camera switching.
     */
    fun togglePinParticipant(uid: String) {
        val currentPinned = _uiState.value.pinnedUid
        _uiState.value = _uiState.value.copy(
            pinnedUid = if (currentPinned == uid) null else uid,
            layoutMode = if (currentPinned != uid) CallLayoutMode.FOCUS else _uiState.value.layoutMode
        )
    }

    fun setAutoCamSwitchEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoCamSwitchEnabled = enabled)
    }

    fun setCompactPipMode(compact: Boolean) {
        _uiState.value = _uiState.value.copy(isCompactPipMode = compact)
    }

    fun setHideNonVideoParticipants(hide: Boolean) {
        _uiState.value = _uiState.value.copy(hideNonVideoParticipants = hide)
    }

    fun setOutputRoute(route: AudioOutputRoute) {
        audioRouting.applyRoute(route)
        _uiState.value = _uiState.value.copy(outputRoute = route)
    }

    /**
     * Resolves which participant should occupy the Featured / Compact PiP slot:
     * 1. Manual pin always wins
     * 2. Active screen sharer
     * 3. Active speaker (if autoCamSwitchEnabled & has video)
     * 4. Last active video participant / remote participant fallback
     */
    fun resolveFeaturedParticipantUid(): String? {
        val s = _uiState.value
        s.pinnedUid?.let { pinned ->
            if (s.participants.any { it.uid == pinned }) return pinned
        }
        s.participants.firstOrNull { it.isScreenSharing }?.let { return it.uid }
        if (s.autoCamSwitchEnabled) {
            s.activeSpeakerUid?.let { sp ->
                val p = s.participants.firstOrNull { it.uid == sp }
                if (p != null && (p.isCameraEnabled || p.isScreenSharing)) return sp
            }
        }
        s.lastActiveVideoUid?.let { last ->
            if (s.participants.any { it.uid == last }) return last
        }
        return s.participants.firstOrNull { !it.isLocal }?.uid ?: s.participants.firstOrNull()?.uid
    }

    /**
     * Updates speaking state and active speaker without turning off silent participants' video!
     */
    fun updateParticipantSpeaking(uid: String, isSpeaking: Boolean, levelDb: Double) {
        val updated = _uiState.value.participants.map { p ->
            if (p.uid == uid) p.copy(isSpeaking = isSpeaking, audioLevelDb = levelDb) else p
        }
        val activeSpeaker = updated
            .filter { it.isSpeaking && !it.isAudioMuted }
            .maxByOrNull { it.audioLevelDb }
            ?.uid
        val lastVideo = if (activeSpeaker != null && updated.firstOrNull { it.uid == activeSpeaker }?.isCameraEnabled == true) {
            activeSpeaker
        } else {
            _uiState.value.lastActiveVideoUid
        }
        _uiState.value = _uiState.value.copy(
            participants = updated,
            activeSpeakerUid = activeSpeaker,
            lastActiveVideoUid = lastVideo
        )
    }

    /**
     * Adjusts simulcast target tier based on estimated network bandwidth (bps / kbps).
     */
    fun applyBandwidthAdaptation(estimatedDownKbps: Int, isMetered: Boolean) {
        val tier = when {
            estimatedDownKbps >= 1500 && !isMetered -> SimulcastTier.HIGH
            estimatedDownKbps >= 450 -> SimulcastTier.MEDIUM
            else -> SimulcastTier.LOW
        }
        _uiState.value = _uiState.value.copy(
            stats = _uiState.value.stats.copy(
                isMetered = isMetered,
                downKbpsEstimate = estimatedDownKbps,
                activeSimulcastTier = tier
            )
        )
        peerConnections.values.forEach { pc ->
            pc.senders.filter { it.track()?.kind() == "video" }.forEach { sender ->
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings.forEach { enc ->
                        when (enc.rid) {
                            "f" -> enc.active = (tier == SimulcastTier.HIGH)
                            "h" -> enc.active = (tier != SimulcastTier.LOW)
                            "q" -> enc.active = true
                        }
                    }
                    sender.setParameters(params)
                }
            }
        }
    }

    private fun startVadMonitoring() {
        vad.startMonitoring(scope) {
            _uiState.value.participants.firstOrNull { it.isLocal }?.isAudioMuted ?: false
        }
        vadSyncJob?.cancel()
        vadSyncJob = scope.launch {
            var lastSpoke = false
            vad.state.collect { v ->
                if (localUid.isNotEmpty()) {
                    updateParticipantSpeaking(localUid, v.isSpeaking, v.levelDb)
                    if (v.isSpeaking != lastSpoke) {
                        lastSpoke = v.isSpeaking
                        syncLocalParticipantState()
                    }
                }
            }
        }
    }

    private fun startNetworkBandwidthMonitor() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(150)
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                applyBandwidthAdaptation(kbps, metered)
            }

            override fun onLost(network: Network) {
                _uiState.value = _uiState.value.copy(status = "RECONNECTING")
            }

            override fun onAvailable(network: Network) {
                if (_uiState.value.status == "RECONNECTING") {
                    _uiState.value = _uiState.value.copy(status = "CONNECTED")
                    peerConnections.forEach { (peerUid, pc) ->
                        createAndSendOffer(pc, peerUid, "CAMERA")
                    }
                }
            }
        }
        networkCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun attachTrackWithSimulcast(pc: PeerConnection, track: VideoTrack, streamId: String) {
        try {
            val init = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                listOf(streamId),
                buildSimulcastEncodings()
            )
            pc.addTransceiver(track, init)
        } catch (_: Exception) {
            pc.addTrack(track, listOf(streamId))
        }
    }

    private fun getOrCreatePeerConnection(peerUid: String, initiateOffer: Boolean): PeerConnection? {
        peerConnections[peerUid]?.let { return it }
        val factory = peerConnectionFactory ?: return null
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    sendSignal(
                        toUid = peerUid,
                        streamKind = "CAMERA",
                        signalType = "ICE",
                        iceCandidate = candidate.sdp,
                        iceSdpMid = candidate.sdpMid,
                        iceSdpMLineIndex = candidate.sdpMLineIndex
                    )
                }

                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        val streamId = mediaStreams?.firstOrNull()?.id ?: ""
                        if (streamId.startsWith("screen_")) {
                            remoteScreenTracks.value = remoteScreenTracks.value + (peerUid to track)
                        } else {
                            remoteCameraTracks.value = remoteCameraTracks.value + (peerUid to track)
                        }
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    _uiState.value = _uiState.value.copy(
                        stats = _uiState.value.stats.copy(iceConnectionState = newState.name)
                    )
                }

                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(g: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(s: org.webrtc.MediaStream?) {}
                override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }
        ) ?: return null

        peerConnections[peerUid] = pc
        localAudioTrack?.let { pc.addTrack(it, listOf("audio_$localUid")) }
        localCameraTrack?.let { attachTrackWithSimulcast(pc, it, "camera_$localUid") }
        localScreenTrack?.let { attachTrackWithSimulcast(pc, it, "screen_$localUid") }

        if (initiateOffer) {
            createAndSendOffer(pc, peerUid, "CAMERA")
        }
        return pc
    }

    private fun createAndSendOffer(pc: PeerConnection, peerUid: String, streamKind: String) {
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(simpleSdpObserver(), sdp)
                sendSignal(
                    toUid = peerUid,
                    streamKind = streamKind,
                    signalType = "OFFER",
                    sdp = sdp.description
                )
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())
    }

    private fun sendSignal(
        toUid: String,
        streamKind: String,
        signalType: String,
        sdp: String? = null,
        iceCandidate: String? = null,
        iceSdpMid: String? = null,
        iceSdpMLineIndex: Int? = null
    ) {
        val repo = repositoryProvider() ?: return
        val callRoomId = _uiState.value.callRoomId ?: return
        if (localUid.isBlank()) return
        scope.launch {
            repo.sendCallSignal(
                FirestoreCallSignal(
                    id = UUID.randomUUID().toString(),
                    callRoomId = callRoomId,
                    fromUid = localUid,
                    toUid = toUid,
                    participantUids = listOf(localUid, toUid),
                    streamKind = streamKind,
                    signalType = signalType,
                    sdp = sdp,
                    iceCandidate = iceCandidate,
                    iceSdpMid = iceSdpMid,
                    iceSdpMLineIndex = iceSdpMLineIndex
                )
            )
        }
    }

    private fun subscribeToFirestoreSignaling(callRoomId: String) {
        val repo = repositoryProvider() ?: return
        signalingJob?.cancel()
        signalingJob = scope.launch {
            try {
                repo.observeIncomingSignals(callRoomId).collect { signals ->
                    signals.filter { processedSignalIds.add(it.id) }.forEach { sig ->
                        handleIncomingSignal(sig)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Signal listener stopped: ${e.message}")
            }
        }

        participantsJob?.cancel()
        participantsJob = scope.launch {
            try {
                repo.observeCallParticipants(callRoomId).collect { remoteList ->
                    val merged = remoteList.map { rp ->
                        ParticipantMediaState(
                            uid = rp.uid,
                            displayName = rp.displayName,
                            photoUrl = rp.photoUrl,
                            isLocal = rp.uid == localUid,
                            isAudioMuted = rp.isAudioMuted,
                            isCameraEnabled = rp.isCameraEnabled,
                            isScreenSharing = rp.isScreenSharing,
                            screenAudioEnabled = rp.screenAudioEnabled,
                            isSpeaking = rp.isSpeaking,
                            audioLevelDb = rp.audioLevelDb,
                            hasCameraTrack = rp.isCameraEnabled,
                            hasScreenTrack = rp.isScreenSharing,
                            videoQuality = rp.videoQuality
                        )
                    }
                    if (merged.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            participants = merged,
                            stats = _uiState.value.stats.copy(
                                activeTracksCount = merged.count { it.isCameraEnabled } +
                                    merged.count { it.isScreenSharing }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Participants listener stopped: ${e.message}")
            }
        }
    }

    private fun handleIncomingSignal(signal: FirestoreCallSignal) {
        val pc = getOrCreatePeerConnection(signal.fromUid, initiateOffer = false) ?: return
        when (signal.signalType) {
            "OFFER" -> {
                val sdp = signal.sdp ?: return
                pc.setRemoteDescription(
                    simpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(ans: SessionDescription) {
                        pc.setLocalDescription(simpleSdpObserver(), ans)
                        sendSignal(signal.fromUid, signal.streamKind, "ANSWER", sdp = ans.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {}
                    override fun onSetFailure(err: String?) {}
                }, MediaConstraints())
            }
            "ANSWER" -> {
                val sdp = signal.sdp ?: return
                pc.setRemoteDescription(
                    simpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
            }
            "ICE" -> {
                val cand = signal.iceCandidate ?: return
                val mid = signal.iceSdpMid ?: ""
                val idx = signal.iceSdpMLineIndex ?: 0
                pc.addIceCandidate(IceCandidate(mid, idx, cand))
            }
        }
    }

    private fun syncLocalParticipantState() {
        val repo = repositoryProvider() ?: return
        val callRoomId = _uiState.value.callRoomId ?: return
        if (localUid.isBlank()) return
        val local = _uiState.value.participants.firstOrNull { it.isLocal } ?: return
        scope.launch {
            repo.upsertCallParticipant(
                FirestoreCallParticipant(
                    uid = localUid,
                    callRoomId = callRoomId,
                    memberUids = roomMembers.ifEmpty { listOf(localUid) },
                    displayName = local.displayName,
                    isAudioMuted = local.isAudioMuted,
                    isCameraEnabled = local.isCameraEnabled,
                    isScreenSharing = local.isScreenSharing,
                    screenAudioEnabled = local.screenAudioEnabled,
                    isSpeaking = local.isSpeaking,
                    audioLevelDb = local.audioLevelDb,
                    videoQuality = _uiState.value.stats.activeSimulcastTier.name.lowercase()
                )
            )
        }
    }

    private fun updateLocalParticipant(transform: (ParticipantMediaState) -> ParticipantMediaState) {
        val updated = _uiState.value.participants.map { p ->
            if (p.isLocal) transform(p) else p
        }
        _uiState.value = _uiState.value.copy(
            participants = updated,
            stats = _uiState.value.stats.copy(
                activeTracksCount = updated.count { it.isCameraEnabled } +
                    updated.count { it.isScreenSharing }
            )
        )
    }

    fun setPermissionWarning(msg: String?) {
        _uiState.value = _uiState.value.copy(permissionWarning = msg)
    }

    fun endCallSession() {
        val roomId = _uiState.value.callRoomId
        val repo = repositoryProvider()
        if (roomId != null && repo != null) {
            scope.launch { repo.endCallRoom(roomId) }
        }
        cleanupAllMedia()
        _uiState.value = DiscordCallUiState(status = "IDLE")
    }

    fun cleanupAllMedia() {
        signalingJob?.cancel()
        participantsJob?.cancel()
        vadSyncJob?.cancel()
        vad.stopMonitoring()
        stopScreenSharing()

        networkCallback?.let { cb ->
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null

        try {
            localCameraCapturer?.stopCapture()
            localCameraCapturer?.dispose()
        } catch (_: Exception) {
        }
        localCameraCapturer = null
        localCameraTrack?.dispose()
        localCameraTrack = null
        localCameraFlow.value = null
        localCameraSource?.dispose()
        localCameraSource = null
        localCameraHelper?.dispose()
        localCameraHelper = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null

        peerConnections.values.forEach { pc ->
            try {
                pc.close()
                pc.dispose()
            } catch (_: Exception) {
            }
        }
        peerConnections.clear()
        remoteCameraTracks.value = emptyMap()
        remoteScreenTracks.value = emptyMap()
        processedSignalIds.clear()

        audioRouting.restoreAudio()
        try {
            CallMediaService.stop(context)
        } catch (_: Exception) {
        }
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    companion object {
        private const val TAG = "WebRtcCallEngine"
    }
}
