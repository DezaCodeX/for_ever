# Complete Application Architecture & Audit Report — `for∞ever♥︎` (`HeartSync`)

## 1. Executive Summary

`for∞ever♥︎` (`HeartSync`, application ID `com.aistudio.heartsync.lvwjzd`, Kotlin namespace `com.dezacodex`) is a native Android application built with **Kotlin 2.2.10**, **Jetpack Compose (Material 3)**, **AndroidX Room 2.7.0**, **Firebase (Auth, Cloud Firestore, Cloud Storage, Cloud Messaging — BOM 34.12.0)**, and **WebRTC (`io.getstream:stream-webrtc-android:1.3.8`)**.

Per the strict engineering mandate:
- **Zero Gemini / Google AI / external LLM SDKs** are used anywhere in dependencies, source code, or configuration.
- Existing application architecture, romantic design language, Room tables, and offline sandbox capabilities were **preserved and hardened** rather than rewritten from scratch.
- Full **Discord-style multi-stream real-time video conferencing** (simultaneous independent webcam + `MediaProjection` screen sharing, dynamic Grid & Focus layouts, real PCM 16-bit RMS/dBFS Voice Activity Detection, anti-flicker active speaker management, participant pinning, PiP/compact automatic camera switching, 3-layer WebRTC Simulcast, device/audio routing, and WebSocket + Firestore signaling) has been integrated directly into the app's call architecture.
- **Firebase Authentication, Firestore, Storage (`avatars`, `chat_images`, `snaps`, `stories`), and Notifications (`MESSAGE`, `CALL`, `STREAK`, `SNAP`, `SCREENSHOT`, `STORY`, `PAIRING`)** are wired with zero-trust `firestore.rules` and automatic offline Room fallback.

---

## 2. Complete Architecture Inspection (Phase 1)

### 2.1 Frontend Architecture (`com.dezacodex.ui`, `com.dezacodex.ui.call`, `com.dezacodex.ui.theme`)
- **Single-Activity Jetpack Compose Architecture**:
  - `MainActivity.kt` hosts `HeartSyncTheme` and `HeartSyncApp(viewModel)`, registers Android Picture-in-Picture (`onUserLeaveHint`, `onPictureInPictureModeChanged`) callbacks, and requests runtime `POST_NOTIFICATIONS` permission on Android 13+ (`TIRAMISU`).
  - `HeartSyncApp.kt` implements top-level screen state routing (`AppScreen`: `SPLASH`, `ONBOARDING`, `WELCOME`, `LOGIN`, `SIGNUP`, `OTP_VERIFY`, `FORGOT_PWD`, `PAIRING`, `MAIN`, `ADMIN`) and the 6-tab couple workspace (`NavigationTab`: `HOME`, `CHAT`, `SNAPS`, `STORIES`, `CALLS`, `PROFILE`), plus global overlays (fullscreen ephemeral Snap viewer, in-app `NotificationsBottomSheet`, `DiscordCallScreen` fullscreen conference stage, and `MinimizedCallFloatingBar` when browsing other tabs mid-call).
  - `DiscordCallScreen.kt` and `DiscordVideoCallScreen.kt` implement the Discord-inspired dark slate stage (`#111214` / `#1E1F22`), dynamic responsive `GridStage` and `FocusStage`, animated `#23A55A` active-speaker rings, `SurfaceViewRenderer` lifecycle management (`SCALE_ASPECT_FIT` for screen shares, `SCALE_ASPECT_BALANCED` / `FILL` for cameras), participant list modal, and device/quality/VAD settings bottom sheet.

### 2.2 State Management & Business Logic (`HeartViewModel.kt`, `DiscordCallController.kt`)
- Reactive unidirectional data flow powered by Kotlin `StateFlow` and `collectAsStateWithLifecycle()`.
- `HeartViewModel` coordinates dual-mode persistence:
  1. **Local-First AndroidX Room Cache (`HeartRepository`)**: Always available offline and in Local Sandbox Mode; ensures instant UI rendering and zero crash startup even when `app/google-services.json` is absent.
  2. **Cloud Firebase Synchronization (`FirestoreHeartRepository`, `FirebaseStorageRepository`, `GoogleAuthManager`)**: Automatically activates when `FirebaseApp` is provisioned (`isCloudConnected == true`), mirroring Firestore real-time snapshots (`observeUser`, `observeCoupleRoomsForCurrentUser`, `observeMessages`, `observeSnaps`, `observeStories`, `observeNotifications`) and uploading media URIs to Firebase Storage buckets (`avatars`, `chat_images`, `snaps`, `stories`).

### 2.3 Database Configuration, Schema & Relationships
1. **Local SQLite via AndroidX Room (`HeartDatabase.kt`, version `4`)**:
   - `users_table` (`User`): Primary key `email`; stores `username`, salted PBKDF2 `passwordHash`, `avatarUrl`, `phone`, `inviteCode`, `connectedPartnerEmail` (1:1 self-referential couple foreign link), `streakCount`, `snapScore`, `totalMomentsCount`, `isGoogleUser`, `isOtpVerified`, `currentTheme`, `age`, `gender`, `pairingOtp`, `pairingOtpExpiry`.
   - `lovers_profiles` (`LoversProfile`): Singleton row (`id = 1`) persisting active session `loggedInUserEmail`, couple display names, `anniversaryDate`, `currentTheme`, `streakCount`, `totalScore`, `lastInteractionTime`, ringtones (`notificationTone`, `callRingtone`, `videoCallRingtone`, `vibrationIntensity`), and `voiceCallWallpaper`.
   - `love_messages` (`LoveMessage`): Auto-generated `id`, `senderEmail`, `receiverEmail`, `text`, `mediaUrl`, `timestamp`, `isDisappearing`, `durationSecs`, `reaction`, `isSavedInChat`, `msgEffect`.
   - `couple_snaps` (`CoupleSnap`): Auto-generated `id`, `senderEmail`, `receiverEmail`, `snapType` (`IMAGE`/`VIDEO`), `description`, `mediaUrl`, `durationSec`, `viewed`, `screenshotAlert`, `isSavedInChat`, `savedInLocalStoragePath`.
   - `couple_stories` (`CoupleStory`): Auto-generated `id`, `senderEmail`, `description`, `imageType`, `mediaUrl`, `timestamp`, `expiresAt` (+24h), `isSavedInChat`, `savedInLocalStoragePath`.
2. **Cloud Firestore Schema (`firebase-blueprint.json` & `FirestoreModels.kt`)**:
   - `/users/{userId}` (`FirestoreUser`) + `/users/{userId}/notifications/{notifId}` (`FirestoreUserNotification`)
   - `/pair_invites/{inviteCode}` (atomic invite claim lookup)
   - `/couple_rooms/{roomId}` (`FirestoreCoupleRoom`, bounded `memberUids` size == 2) with subcollections `messages`, `snaps`, and `stories`
   - `/call_rooms/{callRoomId}` (`FirestoreCallRoom`, bounded `memberUids` size 1..16) with subcollections `participants/{participantUid}` (`FirestoreCallParticipant`) and `signals/{signalId}` (`FirestoreCallSignal` carrying `CAMERA` vs `SCREEN` SDP offers/answers and ICE candidates)

### 2.4 Real-Time Communication, Signaling & Media Services
- **Dual Signaling Architecture**:
  - `WebSocketSignalingClient.kt`: OkHttp WebSocket client (`SIGNALING_WS_URL`) supporting `join`, `leave`, `offer`, `answer`, `ice-candidate`, and `media-state` events with bounded exponential-backoff reconnect (`1s..16s`, max 5 retries).
  - `FirestoreHeartRepository`: Cloud Firestore `/call_rooms/{callRoomId}/signals` and `/participants` real-time snapshot listeners when authenticated with Firebase.
- **Android Foreground Media Services**:
  - `CallMediaService.kt`: `microphone|camera` foreground service keeping audio/video capture alive when minimized or in PiP.
  - `MediaProjectionService.kt`: `mediaProjection` foreground service required on Android 10–15 (SDK 29–36) prior to starting `ScreenCapturerAndroid`.

### 2.5 File Upload & Export Functionality (`FirebaseStorageRepository.kt`)
- Validates content URI readability, MIME type (`image/jpeg`, `image/png`, `image/webp`, `image/gif`, `image/heic`, `video/mp4`, `video/3gpp`, `video/webm`), and size (`<= 15 MB`) before uploading to `avatars/{uid}/`, `chat_images/{uid}/`, `snaps/{uid}/`, or `stories/{uid}/`.
- Exports Snaps and Stories to device `/Download` via `MediaStore.Downloads` on Android Q+ (`SDK >= 29`) with app-scoped fallback on older API levels.

### 2.6 Environment & Build Configuration
- `/app/build.gradle.kts` conditionally applies `com.google.gms.google-services` only when `app/google-services.json` is present so clean checkouts and CI builds succeed without manual setup.
- `/.env.example` defines non-secret `UNCONFIGURED` placeholders (`SIGNALING_WS_URL=UNCONFIGURED`, `TURN_URL=UNCONFIGURED`, `TURN_USER=UNCONFIGURED`, `TURN_PASS=UNCONFIGURED`) consumed via `com.google.android.libraries.mapsplatform.secrets-gradle-plugin` (avoiding empty-value `BuildConfig.java` syntax errors while cleanly skipped by `WebRtcCallEngine` and `DiscordCallController`).

---

## 3. Cloud Provisioning Status & External Blocker Documentation

During automated Firebase provisioning for user-requested project **`involuted-oxygen-g5v9p`** (`region: asia-south1`, admin user `rdccpscollege@gmail.com`):
- Calling `ProvisionFirebase` RPC against `project_id: "involuted-oxygen-g5v9p"` returned the backend precondition error:
  > `GraphQL errors: Precondition check failed.`
- Per strict engineering and Firebase skill rules, we **did not** silently switch to an unrequested GCP project or fabricate credentials. Instead:
  1. All Firebase client repositories (`FirestoreHeartRepository`, `FirebaseStorageRepository`, `GoogleAuthManager`, `HeartSyncMessagingService`), zero-trust `firestore.rules` (including `rdccpscollege@gmail.com` as verified admin), and `firebase-blueprint.json` are completely implemented and verified.
  2. To activate live cloud sync against `involuted-oxygen-g5v9p`, download `google-services.json` for Android package `com.aistudio.heartsync.lvwjzd` from the Firebase Console (`Project Settings -> Your Apps -> Android`), place it at `app/google-services.json`, and deploy `/firestore.rules` (`firebase deploy --only firestore:rules --project involuted-oxygen-g5v9p`).
