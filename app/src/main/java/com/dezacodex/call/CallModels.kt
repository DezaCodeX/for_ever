package com.dezacodex.call

/**
 * Unified models for Discord-style multi-stream real-time video conferencing.
 */

enum class CallLayoutMode {
    GRID,
    FOCUS
}

enum class FocusTarget {
    SCREEN_SHARE,
    PARTICIPANT_CAMERA
}

enum class SimulcastTier(val label: String, val maxBitrateKbps: Int, val scaleDownBy: Double) {
    HIGH("HD 720p", 1500, 1.0),
    MEDIUM("SD 480p", 550, 2.0),
    LOW("LD 240p", 160, 4.0)
}

data class SimulcastLayerConfig(
    val rid: String,
    val active: Boolean,
    val maxBitrateBps: Int,
    val scaleResolutionDownBy: Double,
    val maxFps: Int
)

enum class AudioOutputDevice(val label: String) {
    SPEAKER("Speaker"),
    EARPIECE("Earpiece"),
    BLUETOOTH("Bluetooth"),
    WIRED_HEADSET("Wired Headset")
}

enum class VideoQualityPreset(
    val label: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val maxBitrateBps: Int
) {
    AUTO("Auto (Adaptive)", 1280, 720, 30, 1_800_000),
    HIGH("High (720p30)", 1280, 720, 30, 2_000_000),
    MEDIUM("Medium (480p24)", 854, 480, 24, 900_000),
    LOW("Low (180p15)", 320, 180, 15, 250_000)
}

enum class VadSensitivity(
    val label: String,
    val thresholdDb: Float,
    val marginOverNoiseDb: Float
) {
    LOW("Low (Noisy Room)", -32f, 12f),
    MEDIUM("Balanced", -42f, 8f),
    HIGH("High (Quiet Room)", -50f, 5f)
}

enum class SignalingConnectionState(val label: String) {
    DISCONNECTED("Local Session"),
    CONNECTING("Connecting Signaling..."),
    CONNECTED("WebRTC Signaling Connected"),
    RECONNECTING("Reconnecting..."),
    ERROR("Signaling Unavailable")
}

data class CameraDeviceOption(
    val deviceId: String,
    val label: String,
    val isFrontFacing: Boolean
)

data class MicrophoneDeviceOption(
    val deviceId: String,
    val label: String
)

data class ParticipantStream(
    val participantId: String,
    val displayName: String,
    val isLocal: Boolean = false,
    val webcamTrackId: String? = null,
    val isWebcamEnabled: Boolean = false,
    val screenTrackId: String? = null,
    val isScreenSharing: Boolean = false,
    val isMicMuted: Boolean = false,
    val audioLevel: Float = 0f, // Normalized 0f..1f
    val audioDb: Float = -90f,
    val isSpeaking: Boolean = false,
    val isPinned: Boolean = false,
    val connectionQualityPercent: Int = 100,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Int = 0
) {
    val hasAnyVideo: Boolean
        get() = (isWebcamEnabled && webcamTrackId != null) || (isScreenSharing && screenTrackId != null)
}

data class NetworkStatsSnapshot(
    val rttMs: Int = 0,
    val packetLossPercent: Float = 0f,
    val outgoingBitrateKbps: Int = 0,
    val incomingBitrateKbps: Int = 0,
    val activeSimulcastLayer: String = "high",
    val degradationPreference: String = "MAINTAIN_FRAMERATE",
    val isMetered: Boolean = false,
    val downKbpsEstimate: Int = 2500,
    val activeSimulcastTier: SimulcastTier = SimulcastTier.HIGH,
    val iceConnectionState: String = "NEW",
    val activeTracksCount: Int = 0
)

data class CallUiState(
    val callStatus: String = "IDLE", // IDLE, OUTGOING, INCOMING, CONNECTED
    val callType: String = "VIDEO", // VIDEO, VOICE
    val roomId: String = "",
    val layoutMode: CallLayoutMode = CallLayoutMode.GRID,
    val focusTarget: FocusTarget = FocusTarget.SCREEN_SHARE,
    val focusedParticipantId: String? = null,
    val pinnedParticipantId: String? = null,
    val activeSpeakerId: String? = null,
    val hideNonVideoParticipants: Boolean = false,
    val isInPipMode: Boolean = false,
    val isMinimizedCallWindow: Boolean = false,
    val showParticipantsSheet: Boolean = false,
    val showDeviceSettingsSheet: Boolean = false,
    val localMicMuted: Boolean = false,
    val localCamEnabled: Boolean = true,
    val isScreenSharing: Boolean = false,
    val screenShareAudioEnabled: Boolean = false,
    val availableCameras: List<CameraDeviceOption> = emptyList(),
    val selectedCameraId: String? = null,
    val availableMicrophones: List<MicrophoneDeviceOption> = emptyList(),
    val selectedMicId: String? = null,
    val availableAudioOutputs: List<AudioOutputDevice> = listOf(AudioOutputDevice.SPEAKER, AudioOutputDevice.EARPIECE),
    val selectedAudioOutput: AudioOutputDevice = AudioOutputDevice.SPEAKER,
    val vadSensitivity: VadSensitivity = VadSensitivity.MEDIUM,
    val qualityPreset: VideoQualityPreset = VideoQualityPreset.AUTO,
    val networkStats: NetworkStatsSnapshot = NetworkStatsSnapshot(),
    val signalingState: SignalingConnectionState = SignalingConnectionState.DISCONNECTED,
    val signalingServerUrl: String = "",
    val hasCameraPermission: Boolean = false,
    val hasMicPermission: Boolean = false,
    val callError: String? = null,
    val elapsedSeconds: Long = 0L,
    val participants: List<ParticipantStream> = emptyList()
)
