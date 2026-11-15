package com.dezacodex

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.call.*
import com.dezacodex.ui.AppScreen
import com.dezacodex.ui.HeartViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiscordVideoConferencingTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private lateinit var controller: DiscordCallController
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        runBlocking { TestHelpers.resetDatabaseForTest(app) }
        scope = CoroutineScope(SupervisorJob() + testDispatcher)
        controller = DiscordCallController(app, scope)
    }

    @After
    fun tearDown() {
        controller.hangUpCall()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun startConnectedVideoCall(): DiscordCallController {
        controller.updatePermissions(hasCamera = true, hasMic = true)
        controller.receiveIncomingCall(
            callType = "VIDEO",
            roomId = "room_test_1",
            localUserEmail = "alex@test.app",
            localUserName = "Alex",
            callerDisplayName = "Sophia"
        )
        controller.acceptIncomingCall()
        return controller
    }

    // Scenario 1: Single participant camera join
    @Test
    fun `scenario 01 - single participant joins video call with camera enabled`() {
        controller.updatePermissions(hasCamera = true, hasMic = true)
        controller.startOutgoingCall("VIDEO", "room_1", "alex@test.app", "Alex", null)
        assertEquals("OUTGOING", controller.uiState.value.callStatus)
        TestHelpers.waitUntil(timeoutMs = 3000L) { controller.uiState.value.callStatus == "CONNECTED" }

        val state = controller.uiState.value
        assertEquals(1, state.participants.size)
        assertTrue(state.localCamEnabled)
        assertEquals("Alex", state.participants.first().displayName)
    }

    // Scenario 2: Multiple participants streaming cameras simultaneously in Grid layout
    @Test
    fun `scenario 02 - multiple participants stream independent webcams in grid layout`() {
        startConnectedVideoCall()
        controller.upsertParticipant(
            ParticipantStream("partner_peer", "Sophia", isLocal = false, webcamTrackId = "track_cam_2", isWebcamEnabled = true)
        )
        controller.upsertParticipant(
            ParticipantStream("peer_3", "Jordan", isLocal = false, webcamTrackId = "track_cam_3", isWebcamEnabled = true)
        )

        val state = controller.uiState.value
        assertEquals(CallLayoutMode.GRID, state.layoutMode)
        assertEquals(3, state.participants.size)
        assertEquals(2, state.participants.count { !it.isLocal && it.webcamTrackId != null && it.isWebcamEnabled })
    }

    // Scenario 3: Simultaneous webcam + screen share from same participant without replacing cam
    @Test
    fun `scenario 03 - simultaneous webcam and screen share keeps both tracks independent`() {
        startConnectedVideoCall()
        controller.toggleCamera() // ensure toggled or active
        if (!controller.uiState.value.localCamEnabled) controller.toggleCamera()

        val camEnabledBefore = controller.uiState.value.localCamEnabled
        assertTrue(camEnabledBefore)

        controller.startScreenShare(Intent(), captureAudio = true)

        val state = controller.uiState.value
        val local = state.participants.first { it.isLocal }
        assertTrue("Webcam must remain enabled during screen share", local.isWebcamEnabled)
        assertTrue("Screen share flag must be active", local.isScreenSharing)
        assertNotNull("Screen track ID must be set independently", local.screenTrackId)
        assertEquals(CallLayoutMode.FOCUS, state.layoutMode)
        assertEquals(FocusTarget.SCREEN_SHARE, state.focusTarget)
        assertTrue(state.screenShareAudioEnabled)
    }

    // Scenario 4: Voice Activity Detection DSP (silence vs speech + hangover)
    @Test
    fun `scenario 04 - vad detects speech vs silence and applies hangover smoothing`() {
        val vad = VoiceActivityDetector(VadSensitivity.MEDIUM)
        val silence = ShortArray(320) { 0 }
        val quietResult = vad.processSamples(silence, nowMs = 1000L)
        assertFalse("Zero PCM samples must not trigger speaking", quietResult.isSpeaking)
        assertTrue(quietResult.levelDb <= -80f)

        // Generate 440Hz sine wave at high amplitude (~ -6 dBFS)
        val speech = ShortArray(320) { i -> (sin(i * 0.17) * 16000).toInt().toShort() }
        val loudResult = vad.processSamples(speech, nowMs = 1100L)
        assertTrue("Loud sine PCM should trigger speaking", loudResult.isSpeaking)
        assertTrue(loudResult.levelDb > -20f)

        // Silence immediately after speech stays active during 650ms hangover
        val withinHangover = vad.processSamples(silence, nowMs = 1400L)
        assertTrue("Hangover should prevent chopping off speech ends", withinHangover.isSpeaking)

        // Past hangover duration -> speech ends cleanly
        val pastHangover = vad.processSamples(silence, nowMs = 1900L)
        assertFalse("VAD must release speaking state after hangover expires", pastHangover.isSpeaking)
    }

    // Scenario 5: ActiveSpeakerManager anti-flicker hysteresis
    @Test
    fun `scenario 05 - active speaker manager suppresses rapid flicker and respects margin`() {
        val manager = ActiveSpeakerManager(switchHoldMs = 1200L, switchMarginDb = 4.0f)
        val p1 = ParticipantStream("p1", "Alex", isSpeaking = true, audioDb = -18f)
        val p2 = ParticipantStream("p2", "Sophia", isSpeaking = true, audioDb = -15f) // only +3dB louder

        // Initial speaker becomes p2 (-15dB > -18dB)
        assertEquals("p2", manager.resolveActiveSpeaker(listOf(p1, p2), nowMs = 1000L))

        // p1 gets only 2dB louder and hold time hasn't elapsed -> stays p2 (no flicker!)
        val p1SlightlyLouder = p1.copy(audioDb = -13f)
        assertEquals("p2", manager.resolveActiveSpeaker(listOf(p1SlightlyLouder, p2), nowMs = 1500L))

        // Even after hold time, if margin < 4dB, stays p2
        assertEquals("p2", manager.resolveActiveSpeaker(listOf(p1SlightlyLouder, p2), nowMs = 2500L))

        // After hold time AND >4dB margin -> switches cleanly to p1
        val p1MuchLouder = p1.copy(audioDb = -8f)
        assertEquals("p1", manager.resolveActiveSpeaker(listOf(p1MuchLouder, p2), nowMs = 2600L))
    }

    // Scenario 6: Pinning participant overrides active speaker spotlight & persists
    @Test
    fun `scenario 06 - pinning participant preserves spotlight priority over louder speaker`() {
        startConnectedVideoCall()
        val remote = ParticipantStream("partner_peer", "Sophia", isLocal = false, isWebcamEnabled = true, webcamTrackId = "cam_r", isSpeaking = false, audioDb = -60f)
        val noisyPeer = ParticipantStream("peer_loud", "AlexLoud", isLocal = false, isWebcamEnabled = true, webcamTrackId = "cam_l", isSpeaking = true, audioDb = -5f)
        controller.upsertParticipant(remote)
        controller.upsertParticipant(noisyPeer)

        controller.togglePinParticipant("partner_peer")
        assertEquals("partner_peer", controller.uiState.value.pinnedParticipantId)
        assertEquals("partner_peer", controller.getSpotlightParticipant()?.participantId)

        // Unpin reverts back to highest priority spotlight
        controller.togglePinParticipant("partner_peer")
        assertNull(controller.uiState.value.pinnedParticipantId)
    }

    // Scenario 7: Smart spotlight selection priority chain
    @Test
    fun `scenario 07 - smart spotlight prioritizes screen share over camera over audio-only`() {
        val manager = ActiveSpeakerManager()
        val audioOnlySpeaker = ParticipantStream("a", "AudioSpeaker", isSpeaking = true, audioDb = -10f, isWebcamEnabled = false)
        val screenSharer = ParticipantStream("s", "ScreenSharer", isScreenSharing = true, screenTrackId = "scr_1")
        val camUser = ParticipantStream("c", "CamUser", isLocal = false, isWebcamEnabled = true, webcamTrackId = "cam_1")

        // Speaking without video loses spotlight to active screen sharer
        val spotScreen = manager.selectSpotlightParticipant(listOf(audioOnlySpeaker, camUser, screenSharer), null, "a")
        assertEquals("s", spotScreen?.participantId)

        // When active speaker turns on video, active speaker with video wins over non-speaking camUser
        val speakingVideoUser = camUser.copy(isSpeaking = true, audioDb = -12f)
        val spotSpeakCam = manager.selectSpotlightParticipant(listOf(screenSharer, speakingVideoUser), null, "c")
        assertEquals("c", spotSpeakCam?.participantId)
    }

    // Scenario 8: Layout switching Grid <-> Focus and Hide Non-Video toggle
    @Test
    fun `scenario 08 - layout toggles between grid and focus and filters non-video participants`() {
        startConnectedVideoCall()
        assertEquals(CallLayoutMode.GRID, controller.uiState.value.layoutMode)

        controller.setFocusTarget(FocusTarget.PARTICIPANT_CAMERA, "partner_peer")
        assertEquals(CallLayoutMode.FOCUS, controller.uiState.value.layoutMode)
        assertEquals("partner_peer", controller.uiState.value.focusedParticipantId)

        controller.setLayoutMode(CallLayoutMode.GRID)
        assertEquals(CallLayoutMode.GRID, controller.uiState.value.layoutMode)

        assertFalse(controller.uiState.value.hideNonVideoParticipants)
        controller.toggleHideNonVideoParticipants()
        assertTrue(controller.uiState.value.hideNonVideoParticipants)
    }

    // Scenario 9 & 10: Disabling camera & Muting mic updates state and stops speaking badge
    @Test
    fun `scenario 09 and 10 - toggling camera off and muting microphone updates participant states`() {
        startConnectedVideoCall()
        assertTrue(controller.uiState.value.localCamEnabled)
        assertFalse(controller.uiState.value.localMicMuted)

        controller.toggleCamera()
        assertFalse(controller.uiState.value.localCamEnabled)
        assertFalse(controller.uiState.value.participants.first { it.isLocal }.isWebcamEnabled)

        controller.toggleMicrophone()
        val localAfterMute = controller.uiState.value.participants.first { it.isLocal }
        assertTrue(controller.uiState.value.localMicMuted)
        assertTrue(localAfterMute.isMicMuted)
        assertFalse(localAfterMute.isSpeaking)
        assertEquals(0f, localAfterMute.audioLevel)
    }

    // Scenario 11: Stopping screen share keeps webcam track untouched
    @Test
    fun `scenario 11 - stopping screen share ends only screen stream while webcam stays live`() {
        startConnectedVideoCall()
        controller.startScreenShare(Intent(), captureAudio = false)
        assertTrue(controller.uiState.value.isScreenSharing)

        controller.stopScreenShare()
        val state = controller.uiState.value
        val local = state.participants.first { it.isLocal }
        assertFalse(state.isScreenSharing)
        assertFalse(local.isScreenSharing)
        assertNull(local.screenTrackId)
        assertTrue("Local camera must still be active", state.localCamEnabled)
        assertEquals(CallLayoutMode.GRID, state.layoutMode)
    }

    // Scenario 12: Remote participant disconnect clears stream and pin/active speaker
    @Test
    fun `scenario 12 - participant disconnect cleans up participant list, pin, and active speaker`() {
        startConnectedVideoCall()
        controller.togglePinParticipant("partner_peer")
        controller.handleRemoteAudioLevel("partner_peer", 0.7f)
        assertEquals("partner_peer", controller.uiState.value.activeSpeakerId)

        controller.removeParticipant("partner_peer")
        val state = controller.uiState.value
        assertFalse(state.participants.any { it.participantId == "partner_peer" })
        assertNull(state.pinnedParticipantId)
        assertNull(state.activeSpeakerId)
    }

    // Scenario 13: WebRTC simulcast 3-layer encoding & quality presets
    @Test
    fun `scenario 13 - webrtc simulcast encodings configure 3 quality tiers per preset`() {
        val autoLayers = WebRtcCallEngine.buildSimulcastEncodingsForPreset(VideoQualityPreset.AUTO)
        assertEquals(3, autoLayers.size)
        assertTrue(autoLayers.all { it.active })
        assertEquals(listOf("low", "mid", "high"), autoLayers.map { it.rid })

        val lowLayers = WebRtcCallEngine.buildSimulcastEncodingsForPreset(VideoQualityPreset.LOW)
        assertEquals(1, lowLayers.count { it.active })
        assertEquals("low", lowLayers.first { it.active }.rid)

        startConnectedVideoCall()
        controller.setVideoQualityPreset(VideoQualityPreset.MEDIUM)
        assertEquals(VideoQualityPreset.MEDIUM, controller.uiState.value.qualityPreset)
        assertEquals("medium", controller.uiState.value.networkStats.activeSimulcastLayer)
        assertEquals("BALANCED", controller.uiState.value.networkStats.degradationPreference)
    }

    // Scenario 14: Signaling message parser handles offer, answer, ice-candidate, media-state & malformed json
    @Test
    fun `scenario 14 - signaling client parses sdp offers, ice candidates, media state, and ignores malformed json`() {
        val client = WebSocketSignalingClient()
        val emitted = mutableListOf<SignalingEvent>()
        val job = kotlinx.coroutines.run {
            kotlinx.coroutines.CoroutineScope(testDispatcher).launchCollect(client.events) {
                emitted.add(it)
            }
        }

        client.parseMessage("""{"type":"peer-joined","peerId":"p2","displayName":"Sophia"}""")
        client.parseMessage("""{"type":"offer","peerId":"p2","sdp":"v=0\no=- 1 1 IN IP4 127.0.0.1"}""")
        client.parseMessage("""{"type":"ice-candidate","peerId":"p2","sdpMid":"0","sdpMLineIndex":0,"candidate":"candidate:1 1 UDP 2122260223 10.0.0.1 5000 typ host"}""")
        client.parseMessage("""{"type":"media-state","peerId":"p2","micMuted":true,"camEnabled":true,"screenSharing":true,"webcamStreamId":"w1","screenStreamId":"s1","audioLevel":0.42}""")
        client.parseMessage("""{malformed json""")

        assertEquals(4, emitted.size)
        assertTrue(emitted[0] is SignalingEvent.PeerJoined)
        assertTrue(emitted[1] is SignalingEvent.OfferReceived)
        assertTrue(emitted[2] is SignalingEvent.IceCandidateReceived)
        val media = emitted[3] as SignalingEvent.MediaStateUpdated
        assertTrue(media.micMuted && media.screenSharing)
        assertEquals("s1", media.screenStreamId)
        job.cancel()
    }

    private fun <T> CoroutineScope.launchCollect(
        flow: kotlinx.coroutines.flow.SharedFlow<T>,
        onEach: (T) -> Unit
    ) = kotlinx.coroutines.launch {
        flow.collect { onEach(it) }
    }

    // Scenario 15: Device, VAD sensitivity & audio output switching + Camera permission denial fallback
    @Test
    fun `scenario 15 - permission denial falls back cleanly and device settings update audio and vad`() {
        controller.updatePermissions(hasCamera = false, hasMic = false)
        controller.receiveIncomingCall("VIDEO", "room_denied", "a@b.c", "Alex", "Sophia")
        controller.acceptIncomingCall()

        val afterAccept = controller.uiState.value
        assertEquals("CONNECTED", afterAccept.callStatus)
        assertFalse("Camera must stay off when permission denied", afterAccept.localCamEnabled)
        assertTrue("Mic must remain muted when permission denied", afterAccept.localMicMuted)
        assertNotNull(afterAccept.callError)

        controller.selectAudioOutput(AudioOutputDevice.BLUETOOTH)
        controller.setVadSensitivity(VadSensitivity.HIGH)
        assertEquals(AudioOutputDevice.BLUETOOTH, controller.uiState.value.selectedAudioOutput)
        assertEquals(VadSensitivity.HIGH, controller.uiState.value.vadSensitivity)
    }

    // Scenario 16 & 17: Minimized Call / PiP & Call Hangup + Security Gate on Unpaired Users
    @Test
    fun `scenario 16 and 17 - minimized and pip modes toggle cleanly and hangup resets full call state`() {
        startConnectedVideoCall()
        controller.setMinimizedCallWindow(true)
        assertTrue(controller.uiState.value.isMinimizedCallWindow)

        controller.setPipMode(true)
        assertTrue(controller.uiState.value.isInPipMode)

        controller.hangUpCall()
        val ended = controller.uiState.value
        assertEquals("IDLE", ended.callStatus)
        assertFalse(ended.isScreenSharing)
        assertFalse(ended.isInPipMode)
        assertFalse(ended.isMinimizedCallWindow)
        assertTrue(ended.participants.isEmpty())

        // Verify HeartViewModel enforces couple-only call authorization
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        vm.handleNormalSignUp("Solo", "solo@test.app", "Pass1234", "", 22, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode(vm.pendingLocalOtpHint.value!!)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }

        vm.initiateCall("VIDEO")
        assertNotNull(vm.authError.value)
        assertEquals("IDLE", vm.callUiState.value.callStatus)
    }
}
