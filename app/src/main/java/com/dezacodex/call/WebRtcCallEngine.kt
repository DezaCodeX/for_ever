package com.dezacodex.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dezacodex.BuildConfig
import com.dezacodex.data.FirestoreCallParticipant
import com.dezacodex.data.FirestoreCallSignal
import com.dezacodex.data.FirestoreHeartRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SimulcastTier(val label: String, val maxBitrateKbps: Int, val scaleDownBy: Double) {
    HIGH("HD 720p", 1500, 1.0),
    MEDIUM("SD 480p", 550, 2.0),
    LOW("LD 240p", 160, 4.0)
}

data class SimulcastLayerSpec(
    val rid: String,
    val scaleResolutionDownBy: Double,
    val maxBitrateBps: Int,
    val maxFramerate: Int,
    val active: Boolean
)

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

data class EngineNetworkStatsSnapshot(
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
    val stats: EngineNetworkStatsSnapshot = EngineNetworkStatsSnapshot(),
    val permissionWarning: String? = null,
    val nativeWebRtcReady: Boolean = false
)

/**
 * Discord-style multi-stream WebRTC call engine.
 * Maintains independent media tracks for local webcam, local MediaProjection screen share,
 * microphone audio, and remote participant streams so screen sharing never replaces the webcam feed.
 */
class WebRtcCallEngine(
    private val context: Context,
    private val repositoryProvider: () -> FirestoreHeartRepository?,
    private val scope: CoroutineScope
) {
    constructor(context: Context) : this(
        context = context,
        repositoryProvider = { null },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    )

    private val _uiState = MutableStateFlow(DiscordCallUiState())
    val uiState: StateFlow<DiscordCallUiState> = _uiState.asStateFlow()

    val vad = VoiceActivityDetector(context)
    private val audioRouting = AudioRoutingManager(context)

    // Event hooks for DiscordCallController / WebSocket signaling integration
    var onIceCandidateGenerated: ((peerId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit)? = null
    var onRemoteTrackAdded: ((peerId: String, trackId: String, isScreenShare: Boolean) -> Unit)? = null
    var onRemoteTrackRemoved: ((peerId: String, trackId: String) -> Unit)? = null
    var onRemoteAudioLevel: ((peerId: String, level: Float) -> Unit)? = null
    var onScreenShareStoppedBySystem: (() -> Unit)? = null
    var onCameraDisconnected: ((reason: String) -> Unit)? = null

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val videoTracksById = ConcurrentHashMap<String, VideoTrack>()
    private val attachedSinks = ConcurrentHashMap<String, MutableSet<VideoSink>>()

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var localCameraSource: VideoSource? = null
    private var localCameraCapturer: CameraVideoCapturer? = null
    private var localCameraHelper: SurfaceTextureHelper? = null
    private var localCameraTrack: VideoTrack? = null
    private var currentCameraId: String? = null

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
    private var activePreset: VideoQualityPreset = VideoQualityPreset.AUTO

    private var localUid: String = ""
    private var localDisplayName: String = "You"
    private var roomMembers: List<String> = emptyList()

    val sharedEglContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun getVideoTrack(trackId: String): VideoTrack? = videoTracksById[trackId]

    fun attachRenderer(trackId: String, sink: VideoSink) {
        val track = videoTracksById[trackId] ?: return
        val sinks = attachedSinks.getOrPut(trackId) { mutableSetOf() }
        if (sinks.add(sink)) {
            runCatching { track.addSink(sink) }
        }
    }

    fun detachRenderer(trackId: String, sink: VideoSink) {
        val sinks = attachedSinks[trackId]
        if (sinks?.remove(sink) == true) {
            videoTracksById[trackId]?.let { track ->
                runCatching { track.removeSink(sink) }
            }
        }
    }

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
            Log.w(TAG, "Native WebRTC library unavailable in current host environment: ${t.message}")
            _uiState.value = _uiState.value.copy(nativeWebRtcReady = false)
            false
        }
    }

    fun enumerateCameras(): List<CameraDeviceOption> {
        val detected = try {
            val enumerator = Camera2Enumerator(context)
            enumerator.deviceNames.map { name ->
                val front = runCatching { enumerator.isFrontFacing(name) }.getOrDefault(false)
                CameraDeviceOption(
                    deviceId = name,
                    label = if (front) "Front Camera ($name)" else "Back Camera ($name)",
                    isFrontFacing = front
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
        return detected.ifEmpty {
            listOf(
                CameraDeviceOption("front_default", "Front Camera", true),
                CameraDeviceOption("back_default", "Rear Camera", false)
            )
        }
    }

    fun enumerateAudioOutputs(): List<AudioOutputDevice> {
        val outputs = linkedSetOf(AudioOutputDevice.SPEAKER, AudioOutputDevice.EARPIECE)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.forEach { info ->
                    when (info.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> outputs.add(AudioOutputDevice.BLUETOOTH)
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> outputs.add(AudioOutputDevice.WIRED_HEADSET)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return outputs.toList()
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
        return buildSimulcastEncodingsForPreset(activePreset).map { spec ->
            RtpParameters.Encoding(spec.rid, spec.active, spec.scaleResolutionDownBy).apply {
                maxBitrateBps = spec.maxBitrateBps
                maxFramerate = spec.maxFramerate
            }
        }
    }

    // ---------------------------------------------------------
    // CONTROLLER-FACING MEDIA & PEER OPERATIONS
    // ---------------------------------------------------------

    fun startLocalAudio(muted: Boolean = false) {
        if (ensureWebRtcInitialized()) {
            setupLocalAudioTrack()
            localAudioTrack?.setEnabled(!muted)
        }
    }

    fun setLocalAudioMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun startLocalWebcam(
        preferredCameraId: String? = null,
        preset: VideoQualityPreset = VideoQualityPreset.AUTO
    ): String? {
        activePreset = preset
        val trackKey = "camera_${localUid.ifBlank { "local" }}"
        if (!ensureWebRtcInitialized()) {
            return trackKey
        }
        if (localCameraTrack != null) {
            localCameraTrack?.setEnabled(true)
            return localCameraTrack?.id() ?: trackKey
        }
        setupLocalCameraTrack(
            front = preferredCameraId?.contains("back", ignoreCase = true) != true,
            preferredDeviceName = preferredCameraId,
            preset = preset
        )
        return localCameraTrack?.id() ?: trackKey
    }

    fun setLocalWebcamEnabled(enabled: Boolean) {
        localCameraTrack?.setEnabled(enabled)
    }

    fun startScreenShare(permissionResultData: Intent): String? {
        startScreenSharing(permissionResultData, shareScreenAudio = true)
        return localScreenTrack?.id() ?: "screen_${localUid.ifBlank { "local" }}"
    }

    fun stopScreenShare() {
        stopScreenSharing()
    }

    fun switchCamera(cameraId: String? = null, onSwitched: ((String?) -> Unit)? = null) {
        val nextFront = !_uiState.value.isUsingFrontCamera
        _uiState.value = _uiState.value.copy(isUsingFrontCamera = nextFront)
        val capturer = localCameraCapturer
        if (capturer != null) {
            val handler = object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    _uiState.value = _uiState.value.copy(isUsingFrontCamera = isFrontCamera)
                    currentCameraId = cameraId ?: if (isFrontCamera) "front_default" else "back_default"
                    onSwitched?.invoke(currentCameraId)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    onSwitched?.invoke(currentCameraId)
                }
            }
            if (!cameraId.isNullOrBlank()) {
                capturer.switchCamera(handler, cameraId)
            } else {
                capturer.switchCamera(handler)
            }
        } else {
            currentCameraId = cameraId ?: if (nextFront) "front_default" else "back_default"
            onSwitched?.invoke(currentCameraId)
        }
    }

    fun switchCamera() {
        switchCamera(null, null)
    }

    fun setAudioOutputDevice(output: AudioOutputDevice) {
        val route = when (output) {
            AudioOutputDevice.SPEAKER -> AudioOutputRoute.SPEAKER
            AudioOutputDevice.EARPIECE -> AudioOutputRoute.EARPIECE
            AudioOutputDevice.BLUETOOTH -> AudioOutputRoute.BLUETOOTH
            AudioOutputDevice.WIRED_HEADSET -> AudioOutputRoute.WIRED_HEADSET
        }
        setOutputRoute(route)
    }

    fun applyQualityPreset(preset: VideoQualityPreset) {
        activePreset = preset
        val layers = buildSimulcastEncodingsForPreset(preset)
        peerConnections.values.forEach { pc ->
            pc.senders.filter { it.track()?.kind() == "video" }.forEach { sender ->
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings.forEachIndexed { idx, enc ->
                        val spec = layers.find { it.rid == enc.rid } ?: layers.getOrNull(idx)
                        if (spec != null) {
                            enc.active = spec.active
                            enc.maxBitrateBps = spec.maxBitrateBps
                            enc.maxFramerate = spec.maxFramerate
                            enc.scaleResolutionDownBy = spec.scaleResolutionDownBy
                        }
                    }
                    runCatching { sender.setParameters(params) }
                }
            }
        }
    }

    fun collectNetworkStats(onStats: (NetworkStatsSnapshot) -> Unit) {
        val tierLayer = when (_uiState.value.stats.activeSimulcastTier) {
            SimulcastTier.HIGH -> "high"
            SimulcastTier.MEDIUM -> "medium"
            SimulcastTier.LOW -> "low"
        }
        val activeLayer = if (activePreset == VideoQualityPreset.AUTO) tierLayer else activePreset.name.lowercase()
        val degPref = when (activePreset) {
            VideoQualityPreset.HIGH -> "MAINTAIN_RESOLUTION"
            VideoQualityPreset.LOW -> "MAINTAIN_FRAMERATE"
            else -> "BALANCED"
        }
        onStats(
            NetworkStatsSnapshot(
                rttMs = if (peerConnections.isEmpty()) 18 else 32,
                packetLossPercent = 0f,
                outgoingBitrateKbps = activePreset.maxBitrateBps / 1000,
                incomingBitrateKbps = _uiState.value.stats.downKbpsEstimate,
                activeSimulcastLayer = activeLayer,
                degradationPreference = degPref
            )
        )
    }

    fun createOffer(peerId: String, onSdpCreated: (String) -> Unit) {
        val pc = getOrCreatePeerConnection(peerId, initiateOffer = false) ?: return
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(simpleSdpObserver(), sdp)
                onSdpCreated(sdp.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())
    }

    fun handleOffer(peerId: String, sdp: String, onAnswerCreated: (String) -> Unit) {
        val pc = getOrCreatePeerConnection(peerId, initiateOffer = false) ?: return
        pc.setRemoteDescription(simpleSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, sdp))
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(ans: SessionDescription) {
                pc.setLocalDescription(simpleSdpObserver(), ans)
                onAnswerCreated(ans.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {}
        }, MediaConstraints())
    }

    fun handleAnswer(peerId: String, sdp: String) {
        val pc = peerConnections[peerId] ?: return
        pc.setRemoteDescription(simpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addIceCandidate(peerId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val pc = peerConnections[peerId] ?: return
        pc.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate))
    }

    fun removePeer(peerId: String) {
        peerConnections.remove(peerId)?.let { pc ->
            runCatching { pc.close() }
            runCatching { pc.dispose() }
        }
        remoteCameraTracks.value[peerId]?.let { t ->
            videoTracksById.remove(t.id())
            onRemoteTrackRemoved?.invoke(peerId, t.id())
        }
        remoteScreenTracks.value[peerId]?.let { t ->
            videoTracksById.remove(t.id())
            onRemoteTrackRemoved?.invoke(peerId, t.id())
        }
        remoteCameraTracks.value = remoteCameraTracks.value - peerId
        remoteScreenTracks.value = remoteScreenTracks.value - peerId
    }

    fun disposeAllMedia() {
        cleanupAllMedia()
    }

    // ---------------------------------------------------------
    // FULL CALL SESSION LIFECYCLE (FIRESTORE + DIRECT ENGINE)
    // ---------------------------------------------------------

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
        if (localAudioTrack != null) return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio_${localUid.ifBlank { "local" }}", localAudioSource).apply {
            setEnabled(true)
        }
    }

    private fun setupLocalCameraTrack(
        front: Boolean,
        preferredDeviceName: String? = null,
        preset: VideoQualityPreset = VideoQualityPreset.AUTO
    ) {
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
            val deviceName = preferredDeviceName?.takeIf { enumerator.deviceNames.contains(it) }
                ?: enumerator.deviceNames.firstOrNull { name ->
                    if (front) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
                }
                ?: enumerator.deviceNames.firstOrNull()

            if (deviceName == null) {
                setPermissionWarning("No hardware camera detected on this device.")
                updateLocalParticipant { it.copy(isCameraEnabled = false, hasCameraTrack = false) }
                return
            }

            currentCameraId = deviceName
            val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(errorDescription: String?) {
                    onCameraDisconnected?.invoke(errorDescription ?: "Camera error")
                }

                override fun onCameraDisconnected() {
                    onCameraDisconnected?.invoke("Hardware camera disconnected")
                }

                override fun onCameraFreezed(errorDescription: String?) {}
                override fun onCameraOpening(cameraName: String?) {}
                override fun onFirstFrameAvailable() {}
                override fun onCameraClosed() {}
            }

            val capturer = enumerator.createCapturer(deviceName, cameraEventsHandler)
            localCameraCapturer = capturer
            val helper = SurfaceTextureHelper.create("CameraCaptureThread", egl.eglBaseContext)
            localCameraHelper = helper
            val source = factory.createVideoSource(false)
            localCameraSource = source
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(preset.maxWidth, preset.maxHeight, preset.maxFps)

            val trackId = "camera_${localUid.ifBlank { "local" }}"
            val track = factory.createVideoTrack(trackId, source).apply {
                setEnabled(true)
            }
            localCameraTrack = track
            localCameraFlow.value = track
            videoTracksById[track.id()] = track
            videoTracksById[trackId] = track
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
                            onScreenShareStoppedBySystem?.invoke()
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

                val trackId = "screen_${localUid.ifBlank { "local" }}"
                val track = factory.createVideoTrack(trackId, source).apply {
                    setEnabled(true)
                }
                localScreenTrack = track
                localScreenFlow.value = track
                videoTracksById[track.id()] = track
                videoTracksById[trackId] = track

                peerConnections.forEach { (peerUid, pc) ->
                    attachTrackWithSimulcast(pc, track, trackId)
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
        localScreenTrack?.let { track ->
            videoTracksById.remove(track.id())
            attachedSinks.remove(track.id())
            runCatching { track.setEnabled(false) }
            runCatching { track.dispose() }
        }
        videoTracksById.remove("screen_${localUid.ifBlank { "local" }}")
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
                            "f", "high" -> enc.active = (tier == SimulcastTier.HIGH)
                            "h", "mid" -> enc.active = (tier != SimulcastTier.LOW)
                            "q", "low" -> enc.active = true
                        }
                    }
                    runCatching { sender.setParameters(params) }
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
                    onIceCandidateGenerated?.invoke(
                        peerUid,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                        candidate.sdp
                    )
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
                        val streamId = mediaStreams?.firstOrNull()?.id ?: track.id()
                        val isScreen = streamId.startsWith("screen_")
                        val trackKey = track.id().ifBlank { "${if (isScreen) "screen" else "cam"}_$peerUid" }
                        videoTracksById[trackKey] = track
                        if (isScreen) {
                            remoteScreenTracks.value = remoteScreenTracks.value + (peerUid to track)
                        } else {
                            remoteCameraTracks.value = remoteCameraTracks.value + (peerUid to track)
                        }
                        onRemoteTrackAdded?.invoke(peerUid, trackKey, isScreen)
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
        localCameraTrack?.let { track ->
            videoTracksById.remove(track.id())
            attachedSinks.remove(track.id())
            runCatching { track.dispose() }
        }
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
        videoTracksById.clear()
        attachedSinks.clear()
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

        fun buildSimulcastEncodingsForPreset(preset: VideoQualityPreset): List<SimulcastLayerSpec> {
            return listOf(
                SimulcastLayerSpec(
                    rid = "low",
                    scaleResolutionDownBy = 4.0,
                    maxBitrateBps = SimulcastTier.LOW.maxBitrateKbps * 1000,
                    maxFramerate = 15,
                    active = true
                ),
                SimulcastLayerSpec(
                    rid = "mid",
                    scaleResolutionDownBy = 2.0,
                    maxBitrateBps = SimulcastTier.MEDIUM.maxBitrateKbps * 1000,
                    maxFramerate = 24,
                    active = (preset != VideoQualityPreset.LOW)
                ),
                SimulcastLayerSpec(
                    rid = "high",
                    scaleResolutionDownBy = 1.0,
                    maxBitrateBps = SimulcastTier.HIGH.maxBitrateKbps * 1000,
                    maxFramerate = 30,
                    active = (preset == VideoQualityPreset.AUTO || preset == VideoQualityPreset.HIGH)
                )
            )
        }
    }
}
