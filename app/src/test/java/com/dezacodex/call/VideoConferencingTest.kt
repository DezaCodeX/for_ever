package com.dezacodex.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.ui.call.DiscordVideoCallScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoConferencingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var engine: WebRtcCallEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        engine = WebRtcCallEngine(
            context = context,
            repositoryProvider = { null },
            scope = scope
        )
    }

    @After
    fun tearDown() {
        engine.cleanupAllMedia()
        scope.cancel()
    }

    // Scenario 1: Independent screen sharing and webcam tracks simultaneously
    @Test
    fun `screenShare does not replace webcam and exposes both streams simultaneously`() {
        engine.startCallSession("room_1", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        val camBefore = engine.uiState.value.participants.first { it.isLocal }.isCameraEnabled
        assertThat(camBefore).isTrue()

        engine.startScreenSharing(Intent(), shareScreenAudio = true)

        val local = engine.uiState.value.participants.first { it.isLocal }
        assertThat(local.isCameraEnabled).isTrue()
        assertThat(local.isScreenSharing).isTrue()
        assertThat(local.screenAudioEnabled).isTrue()
        assertThat(local.hasScreenTrack).isTrue()
        assertThat(engine.uiState.value.isScreenSharing).isTrue()
    }

    // Scenario 2: Stop and restart screen sharing cleanly without disturbing webcam or audio
    @Test
    fun `screenShare stop and restart preserves webcam and mic audio`() {
        engine.startCallSession("room_2", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.startScreenSharing(Intent(), shareScreenAudio = true)
        engine.stopScreenSharing()

        var local = engine.uiState.value.participants.first { it.isLocal }
        assertThat(local.isScreenSharing).isFalse()
        assertThat(local.isCameraEnabled).isTrue()
        assertThat(local.isAudioMuted).isFalse()

        engine.startScreenSharing(Intent(), shareScreenAudio = false)
        local = engine.uiState.value.participants.first { it.isLocal }
        assertThat(local.isScreenSharing).isTrue()
        assertThat(local.screenAudioEnabled).isFalse()
        assertThat(local.isCameraEnabled).isTrue()
    }

    // Scenario 3: Multi-participant stream independence & featured priority
    @Test
    fun `multiParticipant independent states preserve separate speaker and camera states`() {
        engine.startCallSession("room_3", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.updateParticipantSpeaking("uid_bob", isSpeaking = true, levelDb = -16.0)
        engine.updateParticipantSpeaking("uid_alice", isSpeaking = false, levelDb = -65.0)

        val alice = engine.uiState.value.participants.first { it.uid == "uid_alice" }
        val bob = engine.uiState.value.participants.first { it.uid == "uid_bob" }
        assertThat(bob.isSpeaking).isTrue()
        assertThat(alice.isSpeaking).isFalse()
        assertThat(alice.isCameraEnabled).isTrue()
        assertThat(bob.isCameraEnabled).isTrue()
    }

    // Scenario 4: Dynamic Grid View & Focus View switching without dropping session
    @Test
    fun `switching between Grid View and Focus View keeps session connected and streams intact`() {
        engine.startCallSession("room_4", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        assertThat(engine.uiState.value.layoutMode).isEqualTo(CallLayoutMode.GRID)

        engine.toggleLayoutMode()
        assertThat(engine.uiState.value.layoutMode).isEqualTo(CallLayoutMode.FOCUS)
        assertThat(engine.uiState.value.status).isEqualTo("CONNECTED")

        engine.toggleLayoutMode()
        assertThat(engine.uiState.value.layoutMode).isEqualTo(CallLayoutMode.GRID)
        assertThat(engine.uiState.value.participants).hasSize(2)
    }

    // Scenario 5: Voice Activity Detector PCM RMS/dBFS + hangover hysteresis
    @Test
    fun `voiceActivityDetector evaluates PCM buffers and smooths silent gaps with hangover`() {
        val vad = VoiceActivityDetector(context)
        vad.setSensitivityThresholdDb(-42.0)

        // Loud speech frame (~-6 dBFS)
        val loud = ShortArray(160) { 16000 }
        val s1 = vad.evaluatePcmBuffer(loud, loud.size, nowMs = 1000L)
        assertThat(s1.isSpeaking).isTrue()
        assertThat(s1.levelDb).isGreaterThan(-10.0)

        // Silence 400ms later stays speaking due to 1100ms hangover smoothing
        val silent = ShortArray(160) { 0 }
        val s2 = vad.evaluatePcmBuffer(silent, silent.size, nowMs = 1400L)
        assertThat(s2.isSpeaking).isTrue()

        // Silence after hangover (>1100ms) stops speaking cleanly
        val s3 = vad.evaluatePcmBuffer(silent, silent.size, nowMs = 2600L)
        assertThat(s3.isSpeaking).isFalse()
    }

    // Scenario 6: Active speaker highlighting picks loudest non-muted speaker and keeps silent video active
    @Test
    fun `activeSpeaker selects loudest speaker without disabling silent participants video`() {
        engine.startCallSession("room_6", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.updateParticipantSpeaking("uid_alice", isSpeaking = true, levelDb = -28.0)
        engine.updateParticipantSpeaking("uid_bob", isSpeaking = true, levelDb = -14.0)

        val state = engine.uiState.value
        assertThat(state.activeSpeakerUid).isEqualTo("uid_bob")
        assertThat(state.participants.all { it.isCameraEnabled }).isTrue()
    }

    // Scenario 7: Participant pinning explicitly overrides automatic active speaker switching
    @Test
    fun `pinning participant overrides automatic speaker camera switching`() {
        engine.startCallSession("room_7", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.togglePinParticipant("uid_alice")
        engine.updateParticipantSpeaking("uid_bob", isSpeaking = true, levelDb = -12.0)

        assertThat(engine.uiState.value.pinnedUid).isEqualTo("uid_alice")
        assertThat(engine.resolveFeaturedParticipantUid()).isEqualTo("uid_alice")

        // Unpinning restores active speaker prioritization
        engine.togglePinParticipant("uid_alice")
        assertThat(engine.uiState.value.pinnedUid).isNull()
        assertThat(engine.resolveFeaturedParticipantUid()).isEqualTo("uid_bob")
    }

    // Scenario 8: Compact/PiP mode automatic camera switching & fallback to last active video
    @Test
    fun `compactPipMode autoCamSwitch resolves active speaker and falls back gracefully when silenced`() {
        engine.startCallSession("room_8", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.setCompactPipMode(true)
        engine.setAutoCamSwitchEnabled(true)

        engine.updateParticipantSpeaking("uid_alice", isSpeaking = true, levelDb = -15.0)
        assertThat(engine.resolveFeaturedParticipantUid()).isEqualTo("uid_alice")

        engine.updateParticipantSpeaking("uid_alice", isSpeaking = false, levelDb = -85.0)
        assertThat(engine.resolveFeaturedParticipantUid()).isEqualTo("uid_alice")
    }

    // Scenario 9: Hide Non-Video Participants preference filters audio-only peers
    @Test
    fun `hideNonVideoParticipants toggles filter state cleanly`() {
        engine.startCallSession("room_9", "uid_alice", "Alice", "uid_bob", "Bob", "VOICE")
        assertThat(engine.uiState.value.hideNonVideoParticipants).isFalse()

        engine.setHideNonVideoParticipants(true)
        assertThat(engine.uiState.value.hideNonVideoParticipants).isTrue()
    }

    // Scenario 10: Camera toggle and front/back switch updates state accurately
    @Test
    fun `toggleCamera and switchCamera update local participant and camera orientation state`() {
        engine.startCallSession("room_10", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        assertThat(engine.uiState.value.isUsingFrontCamera).isTrue()

        engine.switchCamera()
        assertThat(engine.uiState.value.isUsingFrontCamera).isFalse()

        val disabled = engine.toggleCamera()
        assertThat(disabled).isFalse()
        assertThat(engine.uiState.value.participants.first { it.isLocal }.isCameraEnabled).isFalse()
    }

    // Scenario 11: Microphone mute suppresses speaking indicator cleanly
    @Test
    fun `toggleMute sets muted flag and clears local speaking state`() {
        engine.startCallSession("room_11", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.updateParticipantSpeaking("uid_alice", isSpeaking = true, levelDb = -18.0)

        val muted = engine.toggleMute()
        assertThat(muted).isTrue()
        val local = engine.uiState.value.participants.first { it.isLocal }
        assertThat(local.isAudioMuted).isTrue()
        assertThat(local.isSpeaking).isFalse()
    }

    // Scenario 12: Output audio device routing switches Speaker / Earpiece / Bluetooth and restores
    @Test
    fun `setOutputRoute updates AudioOutputRoute between Speaker, Earpiece, and Bluetooth`() {
        engine.startCallSession("room_12", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        assertThat(engine.uiState.value.outputRoute).isEqualTo(AudioOutputRoute.SPEAKER)

        engine.setOutputRoute(AudioOutputRoute.EARPIECE)
        assertThat(engine.uiState.value.outputRoute).isEqualTo(AudioOutputRoute.EARPIECE)

        engine.setOutputRoute(AudioOutputRoute.BLUETOOTH)
        assertThat(engine.uiState.value.outputRoute).isEqualTo(AudioOutputRoute.BLUETOOTH)
    }

    // Scenario 13: Multi-layer WebRTC Simulcast encoding parameters ("q", "h", "f")
    @Test
    fun `buildSimulcastEncodings configures low, medium, and high RIDs`() {
        val encodings = engine.buildSimulcastEncodings()
        assertThat(encodings.map { it.rid }).containsExactly("q", "h", "f").inOrder()
        assertThat(encodings[0].scaleResolutionDownBy).isEqualTo(4.0)
        assertThat(encodings[1].scaleResolutionDownBy).isEqualTo(2.0)
        assertThat(encodings[2].scaleResolutionDownBy).isEqualTo(1.0)
    }

    // Scenario 14: Adaptive bitrate / metered network scales simulcast tier & ICE STUN/TURN setup
    @Test
    fun `bandwidthAdaptation scales simulcast tier and STUN ICE servers exclude UNCONFIGURED TURN`() {
        engine.startCallSession("room_14", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")

        engine.applyBandwidthAdaptation(estimatedDownKbps = 2500, isMetered = false)
        assertThat(engine.uiState.value.stats.activeSimulcastTier).isEqualTo(SimulcastTier.HIGH)

        engine.applyBandwidthAdaptation(estimatedDownKbps = 700, isMetered = true)
        assertThat(engine.uiState.value.stats.activeSimulcastTier).isEqualTo(SimulcastTier.MEDIUM)

        engine.applyBandwidthAdaptation(estimatedDownKbps = 250, isMetered = true)
        assertThat(engine.uiState.value.stats.activeSimulcastTier).isEqualTo(SimulcastTier.LOW)

        val iceServers = engine.buildIceServers()
        assertThat(iceServers.size).isAtLeast(2)
        assertThat(iceServers.any { it.urls.any { u -> u.contains("stun.l.google.com") } }).isTrue()
        assertThat(iceServers.any { it.urls.any { u -> u == "UNCONFIGURED" } }).isFalse()
    }

    // Scenario 15: Hang up cleans up tracks and resets status to IDLE
    @Test
    fun `endCallSession resets all media state cleanly to IDLE`() {
        engine.startCallSession("room_15", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.startScreenSharing(Intent(), shareScreenAudio = true)
        engine.endCallSession()

        assertThat(engine.uiState.value.status).isEqualTo("IDLE")
        assertThat(engine.uiState.value.isScreenSharing).isFalse()
        assertThat(engine.uiState.value.participants).isEmpty()
    }

    // Scenario 16: Full Compose UI Verification for DiscordVideoCallScreen
    @Test
    fun `composeUi DiscordVideoCallScreen renders Grid, simultaneous Screen plus Webcam tiles, Focus View, and Compact PiP`() {
        engine.startCallSession("room_ui", "uid_alice", "Alice", "uid_bob", "Bob", "VIDEO")
        engine.startScreenSharing(Intent(), shareScreenAudio = true)
        engine.setLayoutMode(CallLayoutMode.GRID)

        var hangupCalled = false
        composeRule.setContent {
            DiscordVideoCallScreen(
                engine = engine,
                onHangUp = { hangupCalled = true }
            )
        }
        composeRule.waitForIdle()

        // Both screen tile AND cam tile for Alice must render independently in Grid View!
        composeRule.onNodeWithTag("call_grid_view").assertIsDisplayed()
        composeRule.onNodeWithTag("video_tile_uid_alice_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("video_tile_uid_alice_cam").assertIsDisplayed()
        composeRule.onNodeWithTag("video_tile_uid_bob_cam").assertIsDisplayed()

        // Switch to Focus View
        composeRule.onNodeWithTag("toggle_layout_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("call_focus_view").assertIsDisplayed()

        // Enter Compact PiP mode
        composeRule.onNodeWithTag("toggle_pip_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compact_pip_window").assertIsDisplayed()

        // Expand back and hang up
        composeRule.onNodeWithTag("expand_pip_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("control_hang_up").performClick()
        assertThat(hangupCalled).isTrue()
    }
}
