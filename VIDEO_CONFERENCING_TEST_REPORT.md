# Discord-Style Multi-Stream Video Conferencing Implementation & Test Report

## 1. Architecture Overview (Phase 2)

A complete Discord-inspired multi-stream real-time video conferencing architecture was engineered across:
- **`com.dezacodex.call.CallModels.kt`** — Unified state models (`CallLayoutMode`, `FocusTarget`, `SimulcastTier`, `SimulcastLayerConfig`, `VideoQualityPreset`, `VadSensitivity`, `AudioOutputDevice`, `ParticipantStream`, `NetworkStatsSnapshot`, `CallUiState`).
- **`com.dezacodex.call.WebRtcCallEngine.kt`** — Native GetStream WebRTC (`1.3.8`) multi-track engine managing independent `PeerConnection`, `Camera2Enumerator` webcam capture (`local_webcam_track`), `ScreenCapturerAndroid` `MediaProjection` capture (`local_screen_track`), `AudioSource`/`AudioTrack` microphone capture, 3-layer RTP Simulcast encodings, STUN/TURN ICE builders, and EGL `SurfaceViewRenderer` binding/cleanup.
- **`com.dezacodex.call.DiscordCallController.kt`** — Call session orchestrator integrating WebRTC media callbacks, `WebSocketSignalingClient`, local/remote VAD, `ActiveSpeakerManager`, layout transitions, pinning, PiP/minimized camera selection, and foreground service lifecycle.
- **`com.dezacodex.call.VoiceActivityDetector.kt` & `ActiveSpeakerManager.kt`** — Real 16-bit PCM RMS/dBFS audio analysis (`16 kHz` mono, `20 ms` frames) with adaptive noise floor EMA, configurable sensitivity (`LOW` `-32 dB`, `MEDIUM` `-42 dB`, `HIGH` `-50 dB`), `650 ms..1100 ms` hangover smoothing, and `900 ms` anti-flicker speaker hold hysteresis.
- **`com.dezacodex.call.AudioRoutingManager.kt`, `CallMediaService.kt`, `MediaProjectionService.kt`, `SignalingClient.kt`** — Hardware audio routing (`SPEAKER`, `EARPIECE`, `BLUETOOTH`, `WIRED_HEADSET`), Android 14/15 foreground media services, and OkHttp WebSocket signaling with exponential-backoff reconnect.
- **`com.dezacodex.ui.call.DiscordCallScreen.kt` & `DiscordVideoCallScreen.kt`** — Responsive Grid View & Focus View Compose stage, animated `#23A55A` speaking rings, screen-share `SCALE_ASPECT_FIT` stage + thumbnail strip, participant drawer, device/quality/VAD sheet, and minimized/PiP overlays.

---

## 2. Requirement-by-Requirement Implementation Matrix

| Section | Requirement | Implementation Details | Verification Status |
| :--- | :--- | :--- | :--- |
| **3.1** | **Separate Media Tracks** (Webcam + Screen Share + Mic + Screen Audio) | `WebRtcCallEngine` allocates separate `VideoSource`/`VideoTrack` pairs (`localCameraTrack` vs `localScreenTrack`). Starting `MediaProjection` screen sharing keeps `localCamEnabled == true` and renders **both** tiles simultaneously (`video_tile_<uid>_screen` and `video_tile_<uid>_cam`). Supports stop/restart, permission-denial audio-only fallback, camera disconnect recovery, and full `disposeAllMedia()` cleanup on leave. | **PASS (Automated)** |
| **3.2** | **Dynamic Video Layout** (`GRID` & `FOCUS`) | `GridStage` computes adaptive columns (`1..3` cols by participant tile count & phone/tablet width) with `16:9` aspect containers and avatar fallback when camera is off. `FocusStage` renders active screen share (`SCALE_ASPECT_FIT`, never stretched) or focused camera in primary stage + horizontal/vertical participant strip. Switching layouts never drops or renegotiates the call. | **PASS (Automated + Compose UI)** |
| **3.3** | **Voice Activity Detection (VAD)** | `VoiceActivityDetector` computes real RMS and `20 * log10(rms / 32768)` dBFS from 16-bit PCM frames (plus WebRTC remote `audioLevel` fallback), tracks adaptive ambient noise floor, applies `LOW`/`MEDIUM`/`HIGH` margin thresholds and hangover smoothing, and illuminates a `#23A55A` green border ring on speaking tiles. Zero random timers used. | **PASS (Automated DSP Tests)** |
| **3.4** | **Active Speaker Management** | `ActiveSpeakerManager` ranks currently speaking unmuted participants by audio level, enforces a `900 ms` hold window and `+0.08` switch margin to prevent noise-induced ping-ponging, and preserves user-pinned participants across speaker changes. | **PASS (Automated)** |
| **3.5** | **Automatic Camera Switching** (PiP, Minimized, Compact/Mobile) | `selectCompactDisplayParticipant()` prioritizes: (1) Pinned participant -> (2) Active speaker with enabled camera -> (3) Active speaker avatar card (if camera off) -> (4) Remote participant with video -> (5) Last active video participant, avoiding EGL re-init when `trackId` is unchanged. | **PASS (Automated + Compose UI)** |
| **3.6** | **Bandwidth Optimization & Simulcast** | 3-layer RTP Simulcast (`low` 180p@15fps, `mid` 360p@24fps, `high` 720p@30fps), `MAINTAIN_RESOLUTION` for screen share vs `MAINTAIN_FRAMERATE` for webcam, dynamic tier scaling on metered/low-kbps networks and packet loss (`>8%` / `RTT > 300ms`). **Silent participants' video streams are never stopped.** | **PASS (Automated)** |
| **3.7** | **Complete User Controls** | Mute/unmute mic, enable/disable cam, start/stop screen share, switch front/back or specific camera ID, switch mic device, route speaker/earpiece/Bluetooth/wired headset, switch `GRID`/`FOCUS`, pin/unpin participant, hide/show non-video participants, enter/exit PiP & minimized bar, open participant list, and leave call. | **PASS (Automated + Compose UI)** |

---

## 3. Executed Video Conferencing Test Suites

38 dedicated automated tests across **`DiscordVideoConferencingTest.kt`** (16 scenarios), **`VideoConferencingTest.kt`** (16 scenarios including live `DiscordVideoCallScreen` Compose UI tree assertions), **`ComposeUiFlowsTest.kt`** (end-to-end Calls tab -> live video call -> Grid/Focus toggle -> mute -> hangup), **`ComprehensiveFiveCasePerFunctionTest.kt`** (`callvm_01..05`), and **`firestore.test.js`** (call room access control, P2P `SCREEN`/`CAMERA` SDP signal isolation, and terminal `ended` lock) were executed.

---

## 4. Honest Hardware & External Network Limitations Documented

Per strict WebRTC engineering rules (*"Do not claim that WebRTC functionality works without testing real media connections. Never assume that a local development connection proves internet-wide connectivity."*):
1. **Headless Container Hardware Constraint**: The build/test Linux container has no physical CMOS camera sensor, hardware microphone, Bluetooth headset, or interactive Android `MediaProjectionManager` consent dialog. Native `PeerConnectionFactory` / `EglBase` initialization, DSP PCM waveforms (`440 Hz` sine waves, noise floors, silence gaps), signaling protocol frames (`offer`, `answer`, `ice-candidate`, `media-state`), Simulcast encoding matrices, and Jetpack Compose layout/tile trees were verified in Robolectric + Compose UI tests; physical optical capture and `MediaProjection` frame encoding require execution on two physical Android devices.
2. **Cross-NAT Internet Connectivity (TURN & Signaling)**: Default Google STUN servers (`stun:stun.l.google.com:19302`) are configured out of the box, while `TURN_URL`, `TURN_USER`, `TURN_PASS`, and `SIGNALING_WS_URL` in `.env` (plus `app/google-services.json` for Firestore signaling) must be populated with live production endpoints for symmetric-NAT traversal over mobile carrier networks.
