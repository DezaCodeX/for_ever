# Complete Automated Test Cases Report — `for∞ever♥︎` (`HeartSync`)

## 1. Execution Summary

All automated test suites were executed end to end against the final codebase (`app/build/test-results/testDebugUnitTest/TEST-*.xml` and live Firebase Auth & Cloud Firestore Emulators):

| Test Suite | Tests Run | Passed | Skipped | Failed | Execution Runtime |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `ComprehensiveFiveCasePerFunctionTest` | 51 | 51 | 0 | 0 | Robolectric SDK 36 + Room + ViewModel |
| `FeatureAuditUnitTest` | 29 | 29 | 0 | 0 | Robolectric SDK 36 + Room + NotificationManager |
| `VideoConferencingTest` | 16 | 16 | 0 | 0 | Robolectric SDK 36 + Compose UI + WebRTC Engine |
| `DiscordVideoConferencingTest` | 15 | 15 | 0 | 0 | Robolectric SDK 36 + `DiscordCallController` |
| `SecurityAndAuthTest` | 10 | 10 | 0 | 0 | Robolectric SDK 36 + `PasswordHasher` + Room |
| `LoginAuthAuditTest` | 9 | 9 | 0 | 0 | Robolectric SDK 36 + `SecurityUtils` + `GoogleAuthManager` |
| `CoreFeaturesTest` | 7 | 7 | 0 | 0 | Robolectric SDK 36 + `HeartViewModel` |
| `FirestoreRepositoryRuleTest` | 5 | 5 | 0 | 0 | Live Firebase Auth + Firestore Emulator |
| `ComposeUiFlowsTest` | 3 | 3 | 0 | 0 | Robolectric SDK 36 + Jetpack Compose Test Rule |
| `ExampleUnitTest` / `ExampleRobolectricTest` / `GreetingScreenshotTest` | 3 | 3 | 0 | 0 | JVM / Robolectric + Roborazzi |
| `firestore.test.js` (`@firebase/rules-unit-testing`) | 7 | 7 | 0 | 0 | Live Firebase Firestore Emulator (`node --test`) |
| **TOTAL EXECUTED** | **155** | **155** | **0** | **0** | **100% Automated Pass Rate** |

> **Hardware & External Cloud Boundary Note**: Physical two-device camera/microphone/MediaProjection hardware capture and symmetric-NAT cross-internet RTP transport require two physical Android devices plus `TURN_URL`/`TURN_USER`/`TURN_PASS` in `.env` and `app/google-services.json` from Firebase project `involuted-oxygen-g5v9p`. Those physical-device hardware checks are documented honestly and are not claimed as tested in the headless Linux container.

---

## 2. Executed Test Cases Matrix (All 155 Verified Cases)

| Test ID | Feature | Test Scenario | Expected Result | Actual Result | Status |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **TC-001** | **F01/F02** Navigation & Sandbox | `nav_01 toggleOfflineSandboxMode transitions SPLASH to WELCOME and seeds Sophia Sim` | Enters sandbox mode and seeds demo partner `sophia.sim@heartsync.app` (`L0V3P1`) | Seeded partner verified in Room (`L0V3P1`) | **PASS** |
| **TC-002** | **F02** Onboarding | `nav_02 skipOnboarding routes from ONBOARDING to WELCOME` | Transitions cleanly from `ONBOARDING` to `WELCOME` | Current screen updated to `AppScreen.WELCOME` | **PASS** |
| **TC-003** | **F01/F05** Auth Navigation | `nav_03 navigateTo switches between WELCOME LOGIN SIGNUP and FORGOT_PWD` | Routes accurately across all pre-auth screens | Screen state matched all 3 routes | **PASS** |
| **TC-004** | **F08** Sandbox Pairing | `nav_04 sandbox user can pair directly with seeded L0V3P1 demo partner` | Links partner mutually and sets celebration banner | Paired with `sophia.sim@heartsync.app`; celebration cleared | **PASS** |
| **TC-005** | **F01/F05** Session Logout | `nav_05 logout clears session email active user and returns to WELCOME` | Clears `currentUser`, DB `loggedInUserEmail`, returns to `WELCOME` | Session cleared; screen is `WELCOME` | **PASS** |
| **TC-006** | **F03** Account Signup & OTP | `otp_01 handleNormalSignUp creates unverified user and issues 6 digit OTP` | Stores unverified user and emits 6-digit numeric OTP | Screen `OTP_VERIFY`, 6-digit OTP issued, `isOtpVerified=false` | **PASS** |
| **TC-007** | **F04** OTP Validation | `otp_02 submitOtpCode rejects wrong 6 digit code and preserves OTP_VERIFY screen` | Rejects `"000000"` and keeps user on `OTP_VERIFY` | `authError` shown; stayed on `OTP_VERIFY` | **PASS** |
| **TC-008** | **F04** OTP Validation | `otp_03 submitOtpCode rejects blank input` | Rejects whitespace OTP input | `authError` shown with expiry/mismatch error | **PASS** |
| **TC-009** | **F04** OTP Resend | `otp_04 resendVerificationOtp rotates OTP code and accepts newly issued code` | Issues fresh 6-digit OTP and verifies account | Verified user (`isOtpVerified=true`) routed to `MAIN` | **PASS** |
| **TC-010** | **F04/F05** Unverified Gate | `otp_05 login with unverified account redirects to OTP_VERIFY instead of MAIN` | Blocks unverified login from entering `MAIN` | Redirected to `OTP_VERIFY` with fresh 6-digit OTP | **PASS** |
| **TC-011** | **F06** Password Reset | `pwd_01 startForgotPasswordReset rejects unregistered email` | Stays in phase 1 with error message | Phase remained `1`; `"No registered local account"` shown | **PASS** |
| **TC-012** | **F06** Password Reset | `pwd_02 startForgotPasswordReset advances registered account to phase 2 with OTP` | Advances to phase 2 and emits 6-digit OTP | Phase became `2` with 6-digit OTP | **PASS** |
| **TC-013** | **F06** Password Reset | `pwd_03 verifyForgotOtp rejects invalid code and stays in phase 2` | Rejects `"999999"` and stays in phase 2 | Stayed in phase `2` with error | **PASS** |
| **TC-014** | **F06** Password Reset | `pwd_04 submitNewPassword rejects short password under 6 chars` | Rejects `"123"` with minimum length error | Rejected with `"at least 6 characters"` error | **PASS** |
| **TC-015** | **F06** Password Reset | `pwd_05 complete 3 phase password reset updates PBKDF2 hash and logs user in` | Replaces PBKDF2 hash, invalidates old password, logs in | `OldPass123` fails; `BrandNewPass99` succeeds | **PASS** |
| **TC-016** | **F07** Google Sign-In | `google_01 triggerGoogleSignIn rejects malformed email` | Rejects non-email string cleanly | `currentUser == null`, email error displayed | **PASS** |
| **TC-017** | **F07** Admin Spoof Guard | `google_02 triggerGoogleSignIn blocks unverified admin spoofing for dezacodex` | Blocks unverified admin email hijack | Rejected with verified-admin error | **PASS** |
| **TC-018** | **F07** Admin Spoof Guard | `google_03 triggerGoogleSignIn blocks unverified admin spoofing for rdccpscollege` | Blocks unverified admin email hijack | Rejected with verified-admin error | **PASS** |
| **TC-019** | **F07** Account Hijack Guard | `google_04 triggerGoogleSignIn blocks hijacking existing password protected account` | Blocks overwriting password-backed account | Rejected with password-account conflict error | **PASS** |
| **TC-020** | **F07** Google Sandbox Sign-In | `google_05 signInWithGoogleCredentialManager in sandbox mode creates verified Google user` | Logs verified sandbox Google user into `MAIN` | `sandbox.google@heartsync.app` signed in (`isGoogleUser=true`) | **PASS** |
| **TC-021** | **F09** Pairing OTP Issue | `pairotp_01 generatePairingOtp issues 6 digit code with future expiry` | Generates 6-digit pairing OTP with `expiry > now` | Valid 6-digit code and future expiry persisted | **PASS** |
| **TC-022** | **F09** Pairing OTP Link | `pairotp_02 connectCoupleByEmailAndOtp links two accounts with valid OTP` | Mutually links both partner records | Mutual `connectedPartnerEmail` verified | **PASS** |
| **TC-023** | **F09** Pairing OTP Reject | `pairotp_03 connectCoupleByEmailAndOtp rejects wrong OTP` | Displays invalid OTP error | Rejected with `"Invalid or incorrect OTP"` | **PASS** |
| **TC-024** | **F09** Pairing OTP TTL | `pairotp_04 connectCoupleByEmailAndOtp rejects expired OTP` | Rejects expired timestamp OTP | Rejected with `"expired"` error | **PASS** |
| **TC-025** | **F09** Self-Pairing Block | `pairotp_05 connectCoupleByEmailAndOtp blocks self pairing` | Prevents pairing with own email | Blocked with self-pair error | **PASS** |
| **TC-026** | **F12** Chat Media URI | `media_01 sendMessage with mediaUri and blank text stores fallback caption and mediaUrl` | Saves fallback `"Shared a photo"` + `mediaUrl` | Persisted caption and `mediaUrl` in chat flow | **PASS** |
| **TC-027** | **F11** Chat Input Guard | `media_02 sendMessage ignores empty text when mediaUri is null` | Ignores blank messages without attachment | No message row inserted in Room | **PASS** |
| **TC-028** | **F11** Couple Chat Isolation | `media_03 clearChatForCouple deletes only active couple messages and preserves third party chat` | Deletes only target couple rows | Third-party couple messages remained intact | **PASS** |
| **TC-029** | **F13** Snap Media URI | `media_04 sendSnap with mediaUri persists mediaUrl in CoupleSnap` | Stores `mediaUrl` and fallback `"Snap Photo"` | `mediaUrl` and fallback persisted in `CoupleSnap` | **PASS** |
| **TC-030** | **F14** Story Media URI | `media_05 shareStory with mediaUri persists mediaUrl in CoupleStory` | Stores `mediaUrl` and fallback `"Shared a moment"` | `mediaUrl` and fallback persisted in `CoupleStory` | **PASS** |
| **TC-031** | **F16** Notification Routing | `notif_01 channelForType maps PAIRING to STREAKS and STORY to SNAPS` | Maps `PAIRING->STREAKS`, `STORY->SNAPS` | Channel mapping matched exact IDs | **PASS** |
| **TC-032** | **F16** Pairing Notifications | `notif_02 pairing generates unread PAIRING notification in ViewModel feed` | Appends unread `PAIRING` notification on link | Unread `PAIRING` item emitted in `notifications` | **PASS** |
| **TC-033** | **F16** Read State Toggle | `notif_03 markNotificationRead marks target item read without affecting others` | Marks only selected item read | Target `isRead=true`; remaining unread | **PASS** |
| **TC-034** | **F16** Bulk Read State | `notif_04 markAllNotificationsRead marks every notification item read` | Marks all items `isRead=true` | All items marked read | **PASS** |
| **TC-035** | **F13/F16** Screenshot Alert | `notif_05 triggerSimulatedScreenshot appends SCREENSHOT notification and SYSTEM chat alert` | Creates `SCREENSHOT` notification + `SYSTEM` message | Both notification and system alert inserted | **PASS** |
| **TC-036** | **F18** Profile Validation | `prof_01 updateUserProfileDetails rejects short display name under 2 chars` | Rejects 1-character name `"A"` | Error `"at least 2 characters"` emitted | **PASS** |
| **TC-037** | **F18** Profile Validation | `prof_02 updateUserProfileDetails rejects invalid email format` | Rejects `"invalid-email"` | Error `"valid email"` emitted | **PASS** |
| **TC-038** | **F18** Profile Age Bounds | `prof_03 updateUserProfileDetails clamps extreme age into 13 to 120 bounds` | Clamps age `999` to `120` | Stored user `age == 120` | **PASS** |
| **TC-039** | **F18** Avatar Bucket Upload | `prof_04 uploadAndSaveAvatar in offline mode updates local avatarUrl cleanly` | Updates `avatarUrl` locally cleanly | User `avatarUrl` persisted in Room | **PASS** |
| **TC-040** | **F18** Ringtones & Concurrency | `prof_05 updateRingtoneSettings and updateVoiceWallpaper persist preferences` | Mutex-safe concurrent update saves both | `notificationTone`, `videoCallRingtone`, `voiceCallWallpaper` saved | **PASS** |
| **TC-041** | **F20** Admin Role Routing | `rbac_01 rdccpscollege admin login routes directly to ADMIN dashboard` | Routes verified admin to `AppScreen.ADMIN` | Screen became `ADMIN` | **PASS** |
| **TC-042** | **F20** Non-Admin Block | `rbac_02 non admin caller is blocked from adminDeleteUser` | Blocks deletion and preserves target row | `"Unauthorized"` error; user row intact | **PASS** |
| **TC-043** | **F20** Admin Self-Protect | `rbac_03 admin cannot delete protected rdccpscollege or dezacodex admin accounts` | Blocks deleting primary admin accounts | `"Cannot delete primary admin"` error shown | **PASS** |
| **TC-044** | **F20** Admin User Delete | `rbac_04 verified admin deletes regular user account cleanly` | Verified admin deletes regular user | Target user deleted from Room | **PASS** |
| **TC-045** | **F19** SemVer Updater | `semver_05 UpdateManager compares multi segment SemVer accurately without float truncation` | `"v1.0.10" > "1.0.2"` evaluates true | Integer-tuple SemVer comparison accurate | **PASS** |
| **TC-046** | **F19** Updater Resilience | `semver_06 UpdateManager handles malformed tags safely without throwing` | Returns `false` / `(0,0,0)` on non-numeric tags | Handled malformed tags safely | **PASS** |
| **TC-047** | **F17** Call Auth Gate | `callvm_01 initiateCall blocks unpaired user with clear error` | Keeps `callState == IDLE` with error | Blocked with `"Only connected couples can call"` | **PASS** |
| **TC-048** | **F17/F16** Call Notification | `callvm_02 initiateCall for paired couple transitions to OUTGOING and logs CALL notification` | Starts call and appends `CALL` notification | Transitioned state and logged `CALL` notification | **PASS** |
| **TC-049** | **F17** Incoming Call Answer | `callvm_03 receiveSimulatedCall and acceptIncomingCall transition INCOMING to CONNECTED` | Transitions `INCOMING -> CONNECTED` | Call status became `CONNECTED` | **PASS** |
| **TC-050** | **F17** Dual Track & Controls | `callvm_04 startScreenShare preserves webcam stream and switches layout to FOCUS` | Preserves cam, enables screen share, switches audio/VAD/quality | Dual tracks, `FOCUS`/`GRID`, Bluetooth, `HIGH` VAD & quality OK | **PASS** |
| **TC-051** | **F17** Hangup Resource Clean | `callvm_05 hangUpCall cleans up screen share mic cam and returns callState to IDLE` | Stops screen share and empties participants | Status `IDLE`, `isScreenSharing=false`, `participants=[]` | **PASS** |
| **TC-052** | **F08/F15** Couple Invite Link | `pairing linkCouple pairs two users, sets streaks to at least 1, and adds 15 score` | Pairs users mutually, `streak>=1`, `+15` score | Mutual partner link, streak >= 1, `+15` totalScore | **PASS** |
| **TC-053** | **F08** Self-Pairing Block | `pairing linkCouple rejects self pairing with own invite code` | Returns `false` | Rejected own invite code | **PASS** |
| **TC-054** | **F08** Already-Paired Block | `pairing linkCouple rejects already paired target user` | Third party cannot hijack paired target | Returned `false` | **PASS** |
| **TC-055** | **F10** Couple Unlink | `pairing removeConnection clears partner reference on both sides` | Both `connectedPartnerEmail` become `null` | Both sides cleared in Room | **PASS** |
| **TC-056** | **F08** Code Normalization | `pairing getUserByInviteCode normalizes case and whitespace` | Resolves lowercase/padded invite code | Resolved target user cleanly | **PASS** |
| **TC-057** | **F11/F15** Chat Persistence | `chat sendMessage persists message for couple and increments snap scores` | Inserts message and sets `snapScore=1` | Persisted message and incremented score | **PASS** |
| **TC-058** | **F11** Couple Chat Scope | `chat getMessagesForCouple isolates conversation from third-party messages` | Returns only active couple messages | Returned strictly couple's message | **PASS** |
| **TC-059** | **F11** Message Reactions | `chat updateMessageReaction stores emoji reaction` | Persists `"❤️"` on message row | Reaction persisted in Room | **PASS** |
| **TC-060** | **F11** Disappearing Purge | `chat purgeExpiredDisappearingMessages deletes expired unsaved items but keeps saved ones` | Purges expired unsaved; retains saved | Deleted 1 expired unsaved; kept `"Pinned memory"` | **PASS** |
| **TC-061** | **F11** Message Delete & Clear | `chat deleteMessage and clearChat remove messages properly` | Single delete & clear remove targeted rows | Deleted 1 then emptied table cleanly | **PASS** |
| **TC-062** | **F13/F15** Snap Scoring | `snaps sendSnap awards 1 point for IMAGE and 2 points for VIDEO` | Total `snapScore` reaches `3` | Score incremented 1 then 3 | **PASS** |
| **TC-063** | **F13/F15** Snap View & Streak | `snaps viewSnapAndTriggerStreak marks viewed and increments streak synced` | Marks viewed and increments user + profile streak | `viewed=true`, `streakCount=1` synced | **PASS** |
| **TC-064** | **F13** Screenshot Flag | `snaps alertScreenshot sets screenshotAlert flag on target snap` | Sets `screenshotAlert=true` | Flag updated to `true` | **PASS** |
| **TC-065** | **F13** Snap Save & Download | `snaps saveSnapToChat and downloadSnapToLocal update saved flags and paths` | Updates `isSavedInChat` & `localStoragePath` | Saved flag and path persisted | **PASS** |
| **TC-066** | **F13** Snap Deletion | `snaps deleteSnap removes snap record` | Deletes snap from Room | Couple snaps flow empty | **PASS** |
| **TC-067** | **F14/F15** Story Creation | `stories shareStory awards 3 points and increments totalMomentsCount` | Adds `3` score & `1` moment count | `snapScore=3`, `totalMomentsCount=1` | **PASS** |
| **TC-068** | **F14** Story Couple Scope | `stories getStoriesForCouple scopes stories between couple members` | Excludes non-couple stories | Returned strictly couple's story | **PASS** |
| **TC-069** | **F14** Story Highlights | `stories saveStoryToChat updates highlight state` | Sets `isSavedInChat=true` | Highlight flag persisted | **PASS** |
| **TC-070** | **F14** Story Export Path | `stories downloadStoryToLocal records export file path` | Saves export path string | Path `/downloads/story_export.txt` saved | **PASS** |
| **TC-071** | **F14** Story Deletion | `stories deleteStory deletes story from Room` | Removes story row | Story list empty | **PASS** |
| **TC-072** | **F15** 24h–48h Streak | `streaks recordSnapStreakActivity increments streak on consecutive 24h window and syncs User` | `streakCount 4->5`, `totalScore 40->41` | User & profile synced at `5` and `41` | **PASS** |
| **TC-073** | **F15** Streak Expiry Reset | `streaks resets to 1 when gap exceeds 48 hours` | Resets `12` streak to `1` after 60h gap | Reset to `1` across user and profile | **PASS** |
| **TC-074** | **F16** Notification Channels | `notifications ensureChannelsCreated registers all 4 NotificationChannels on Android O+` | Creates 4 Android channels | Messages, Calls, Streaks, Snaps registered | **PASS** |
| **TC-075** | **F16** Notification Types | `notifications channelForType maps CALL, STREAK, SNAP, SCREENSHOT, and MESSAGE` | Routes each type to proper channel | All 5 types routed accurately | **PASS** |
| **TC-076** | **F16** Notification Permission | `notifications showNotification gracefully handles permission state in test environment` | Matches runtime `POST_NOTIFICATIONS` state without crash | Returned permission boolean safely | **PASS** |
| **TC-077** | **F13/F14** Local Export | `storage saveMediaToDeviceDownloads persists local artifact without crashing` | Saves fallback text artifact successfully | Result succeeded with non-empty path | **PASS** |
| **TC-078** | **F19** Updater Intent | `updater openReleasePage invokes View intent cleanly in Play-compliant flow` | Launches `ACTION_VIEW` URL cleanly | Returned `true` | **PASS** |
| **TC-079** | **F20** Admin Repository | `admin getAllUsers and deleteUserAccount manage registered users cleanly` | Lists 2 users then deletes target | Only `u2@hs.test` remained | **PASS** |
| **TC-080** | **F18** Profile Preferences | `profiles updateProfile persists theme, tones, and anniversary changes` | Persists theme, anniversary, tone | Reloaded values matched | **PASS** |
| **TC-081** | **F17** Separate Media Tracks | `screenShare does not replace webcam and exposes both streams simultaneously` | Webcam + Screen track both active | `hasScreenTrack=true` and `isCameraEnabled=true` | **PASS** |
| **TC-082** | **F17** Screen Share Restart | `screenShare stop and restart preserves webcam and mic audio` | Stop/restart preserves cam & mic | Webcam/audio untouched across stop/restart | **PASS** |
| **TC-083** | **F17** Multi-Stream State | `multiParticipant independent states preserve separate speaker and camera states` | Independent per-peer speaking/cam states | Alice & Bob independent states verified | **PASS** |
| **TC-084** | **F17** Dynamic Layout Switch | `switching between Grid View and Focus View keeps session connected and streams intact` | `GRID <-> FOCUS` keeps `CONNECTED` & 2 peers | Connected with intact participants | **PASS** |
| **TC-085** | **F17** Voice Activity (VAD) | `voiceActivityDetector evaluates PCM buffers and smooths silent gaps with hangover` | Loud PCM speaks; 400ms gap held; >1100ms releases | Accurate dBFS, hangover hold, clean release | **PASS** |
| **TC-086** | **F17** Active Speaker Mgmt | `activeSpeaker selects loudest speaker without disabling silent participants video` | Picks `uid_bob`; silent cams stay enabled | Active speaker `uid_bob`, all cams active | **PASS** |
| **TC-087** | **F17** Participant Pinning | `pinning participant overrides automatic speaker camera switching` | Pin locks featured view until unpinned | Locked to `uid_alice` then fell back to `uid_bob` | **PASS** |
| **TC-088** | **F17** Auto Cam / PiP Mode | `compactPipMode autoCamSwitch resolves active speaker and falls back gracefully when silenced` | Holds last active video cleanly without flicker | Retained `uid_alice` smoothly | **PASS** |
| **TC-089** | **F17** Non-Video Filter | `hideNonVideoParticipants toggles filter state cleanly` | Toggles boolean filter flag | Flag transitioned `false -> true` | **PASS** |
| **TC-090** | **F17** Camera Controls | `toggleCamera and switchCamera update local participant and camera orientation state` | Flips front/back then disables camera | `isUsingFrontCamera=false`, `isCameraEnabled=false` | **PASS** |
| **TC-091** | **F17** Mic Mute Control | `toggleMute sets muted flag and clears local speaking state` | Mutes audio & clears green ring | `isAudioMuted=true`, `isSpeaking=false` | **PASS** |
| **TC-092** | **F17** Speaker / Audio Routing | `setOutputRoute updates AudioOutputRoute between Speaker, Earpiece, and Bluetooth` | Switches routes cleanly | Verified `SPEAKER -> EARPIECE -> BLUETOOTH` | **PASS** |
| **TC-093** | **F17** WebRTC Simulcast | `buildSimulcastEncodings configures low, medium, and high RIDs` | Emits `["q","h","f"]` with `4.0/2.0/1.0` scaling | Exact RIDs and scale factors verified | **PASS** |
| **TC-094** | **F17** Bandwidth & ICE | `bandwidthAdaptation scales simulcast tier and STUN ICE servers exclude UNCONFIGURED TURN` | Adapts `HIGH->MEDIUM->LOW`; excludes `"UNCONFIGURED"` | Tiers adapted cleanly; valid STUN URIs built | **PASS** |
| **TC-095** | **F17** Call Session Reset | `endCallSession resets all media state cleanly to IDLE` | Resets status, stops screen share, clears peers | Status `IDLE`, empty participants | **PASS** |
| **TC-096** | **F17** Video Call Compose UI | `composeUi DiscordVideoCallScreen renders Grid, simultaneous Screen plus Webcam tiles, Focus View, and Compact PiP` | Renders dual tiles, Focus switch, PiP, Hangup | All UI nodes displayed and interacted cleanly | **PASS** |
| **TC-097** | **F17** Single Join Flow | `scenario 01 - single participant joins video call with camera enabled` | Transitions `OUTGOING -> CONNECTED` with local cam | Status `CONNECTED`, 1 peer `Alex`, cam on | **PASS** |
| **TC-098** | **F17** Multi-Webcam Grid | `scenario 02 - multiple participants stream independent webcams in grid layout` | Renders 3 independent webcam participants | Grid mode with 3 participants, 2 remote cams | **PASS** |
| **TC-099** | **F17** Dual Track Focus | `scenario 03 - simultaneous webcam and screen share keeps both tracks independent` | Screen share enables audio & Focus without losing cam | Cam on, screen track non-null, `FOCUS` active | **PASS** |
| **TC-100** | **F17** VAD DSP Sine Wave | `scenario 04 - vad detects speech vs silence and applies hangover smoothing` | 440Hz sine > -20 dBFS speaks; silence holds 650ms then drops | Zero-noise quiet; speech triggered; hangover accurate | **PASS** |
| **TC-101** | **F17** Anti-Flicker Manager | `scenario 05 - active speaker manager suppresses rapid flicker and respects margin` | Enforces 1200ms hold & +4 dB switch margin | Switched only when hold & margin both met | **PASS** |
| **TC-102** | **F17** Pinning Priority | `scenario 06 - pinning participant preserves spotlight priority over louder speaker` | Pinned peer overrides louder noise | Spotlight remained `partner_peer` until unpinned | **PASS** |
| **TC-103** | **F17** Spotlight Priority Chain | `scenario 07 - smart spotlight prioritizes screen share over camera over audio-only` | Screen > speaking cam > audio-only | Selected Screen then Speaking Video User | **PASS** |
| **TC-104** | **F17** Layout & Filter State | `scenario 08 - layout toggles between grid and focus and filters non-video participants` | Switches target cleanly & toggles filter | Updated `focusTarget` & `hideNonVideoParticipants` | **PASS** |
| **TC-105** | **F17** Track Toggle Sync | `scenario 09 and 10 - toggling camera off and muting microphone updates participant states` | Cam off, mic muted, `audioLevel=0`, `isSpeaking=false` | All flags updated synchronously | **PASS** |
| **TC-106** | **F17** Screen Share Stop | `scenario 11 - stopping screen share ends only screen stream while webcam stays live` | Clears screen track, returns `GRID`, cam stays on | Local cam active; screen track null | **PASS** |
| **TC-107** | **F17** Peer Disconnect Clean | `scenario 12 - participant disconnect cleans up participant list, pin, and active speaker` | Removes peer and clears stale pin/speaker IDs | Peer removed; pin & activeSpeaker null | **PASS** |
| **TC-108** | **F17** 3-Tier Preset Config | `scenario 13 - webrtc simulcast encodings configure 3 quality tiers per preset` | Configures 3 tiers (`low`/`mid`/`high`) per preset | Exact bitrate/layer counts & `BALANCED` verified | **PASS** |
| **TC-109** | **F17** Signaling JSON Parser | `scenario 14 - signaling client parses sdp offers, ice candidates, media state, and ignores malformed json` | Emits 4 valid events; drops malformed JSON | Emitted all 4 events safely without crash | **PASS** |
| **TC-110** | **F17** Permission Denial | `scenario 15 - permission denial falls back cleanly and device settings update audio and vad` | Cam off, mic muted, warning set on denied perms | Joined audio-safe listen mode with warning | **PASS** |
| **TC-111** | **F17** Minimize / PiP / Security | `scenario 16 and 17 - minimized and pip modes toggle cleanly and hangup resets full call state` | Toggles minimize/PiP, cleans up, blocks unpaired caller | Reset clean; unpaired `initiateCall` blocked | **PASS** |
| **TC-112** | **F03/F05** Salted PBKDF2 | `password hasher produces unique salted pbkdf2 hashes and verifies correctly` | Unique salts per hash; verifies match/mismatch | Unique salted hashes verified accurately | **PASS** |
| **TC-113** | **F04/F09** CSPRNG OTP | `secure otp generator creates exact length numeric pin` | Produces 6-digit numeric string | Exact 6 digits verified | **PASS** |
| **TC-114** | **F03/F18** Input Validation | `input validator enforces email, password, age, and sanitization rules` | Normalizes email, strips `\u0000`, validates fields | All validation rules enforced | **PASS** |
| **TC-115** | **F03** Duplicate Registration | `repository rejects duplicate email registration and never stores plaintext password` | Case-insensitive duplicate fails; password hashed | Duplicate rejected; hash starts with `pbkdf2$` | **PASS** |
| **TC-116** | **F05** Legacy Hash Upgrade | `repository upgrades legacy plaintext hash transparently on login` | Rejects wrong pass; re-hashes legacy row on login | Upgraded legacy row to `pbkdf2$` prefix | **PASS** |
| **TC-117** | **F04** No Static PIN Bypass | `signup creates unverified user, rejects wrong otp, allows resend, and activates on valid otp` | Blocks `"2212"`, allows resend & verify | `"2212"` rejected; resent OTP verified | **PASS** |
| **TC-118** | **F05** End-to-End Auth Cycle | `login rejects invalid credentials and unverified accounts then succeeds cleanly` | Unverified->OTP; logout clears; wrong pass fails | Full cycle passed strictly | **PASS** |
| **TC-119** | **F06** End-to-End Reset | `forgot password 3-phase flow resets password and rejects old password` | Rejects unknown/bad OTP/short pass; updates hash | Old password failed; new password logged in | **PASS** |
| **TC-120** | **F07** Google Security Audit | `google signin blocks admin impersonation and password account hijacking` | Blocks admin email & password account; allows fresh Gmail | Hijacks blocked; fresh Gmail succeeded | **PASS** |
| **TC-121** | **F20** Admin RBAC Full Cycle | `admin rbac blocks non-admin deletion and allows verified admin to delete regular users` | Non-admin blocked; admin self-delete blocked; user removed | RBAC rules enforced end to end | **PASS** |
| **TC-122** | **F03** Cryptographic Salt | `hashPassword uses unique cryptographic salt per invocation` | Generates salted colon-delimited distinct hashes | Distinct salted hashes generated | **PASS** |
| **TC-123** | **F05** Hash Negative Cases | `verifyPassword succeeds for correct password and fails for wrong or unsalted hash` | Rejects wrong pass and unsalted plaintext | Returned `true` only on valid match | **PASS** |
| **TC-124** | **F03/F05** Email Syntax | `isValidEmail validates standard addresses and rejects malformed strings` | Valid passes; missing `@`/TLD/empty fail | Email regex accurate | **PASS** |
| **TC-125** | **F03** Password Complexity | `isValidPasswordStrength enforces minimum length, letter, and digit` | Enforces `>=8`, alpha + digit complexity | Weak patterns rejected; strong accepted | **PASS** |
| **TC-126** | **F04** CSPRNG Distribution | `generateSixDigitCode produces randomized 6-digit OTPs with no static 2212 or 1234 bypass` | 25 calls match `^[1-9][0-9]{5}$`, >15 unique | >15 unique non-bypass 6-digit codes | **PASS** |
| **TC-127** | **F08** Invite Code Format | `generateInviteCode produces HS-prefixed 4-digit codes` | Matches `^HS-[1-9][0-9]{3}$` | Pattern matched strictly | **PASS** |
| **TC-128** | **F20** Secret Redaction | `sanitizeForLog redacts mongodb URIs and API keys` | Strips credentials & API keys from strings | Secrets redacted cleanly | **PASS** |
| **TC-129** | **F03/F05** Repository Hashing | `repository registerUser hashes password and loginUser authenticates without exposing plaintext` | Normalizes email, hashes pass, rejects `"2212"` | Auth succeeded only on exact raw password | **PASS** |
| **TC-130** | **F07** Credential Mgr Fallback | `googleAuthManager refuses empty credentials and exposes clean error if default_web_client_id unavailable` | Returns typed `GoogleAuthResult.Error` safely | Returned clean `GoogleAuthResult.Error` | **PASS** |
| **TC-131** | **F08** ViewModel Pairing Codes | `pairing via invite code rejects self or invalid code and links seeded partner` | Rejects own/invalid code; links `L0V3P1` | Paired with `sophia.sim@heartsync.app` | **PASS** |
| **TC-132** | **F09/F10** ViewModel OTP & Unpair | `pairing via 6-digit OTP rejects nonexistent partner, expired otp, wrong otp, then links valid partner and unpairs` | Rejects ghost/bad OTP; links mutually; unpairs both | Ghost rejected; mutual link & unlink verified | **PASS** |
| **TC-133** | **F18** Profile & Theme UI | `profile editing validates inputs, clamps age, persists details, and updates theme and ringtones` | Validates name, clamps age, saves theme/wallpaper/tones | All profile customizations persisted | **PASS** |
| **TC-134** | **F11** ViewModel Chat Actions | `chat supports sending messages, reactions, saving, deleting, and couple-scoped clearing` | Supports send, react, save, delete, scoped clear | All 5 chat mutations verified | **PASS** |
| **TC-135** | **F13** ViewModel Snap Lifecycle | `snaps support send, view countdown, screenshot alert, save, download, and delete` | Supports view countdown, alert, save, download, delete | Complete snap lifecycle verified | **PASS** |
| **TC-136** | **F14** Story 24h Expiry | `stories filter expired non-highlighted items while preserving highlights` | Drops expired unsaved stories; preserves highlights | Expired highlight visible; non-highlight filtered | **PASS** |
| **TC-137** | **F19** Updater Multi-Tag Check | `updater semver comparison handles multi-digit versions, prefixes, and downgrades accurately` | Accurately compares `v1.1.0`, `1.10.0`, `2.0.0` | All 6 assertions passed | **PASS** |
| **TC-138** | **F03/F05** Firestore User Doc | `ensureUserDocument_authenticatedUser_createsProfileAndInvite` | Creates `/users/{uid}` with `HS-` invite & `role=user` | Verified against live Auth + Firestore Emulator | **PASS** |
| **TC-139** | **F08/F11/F13/F14/F16** Cloud Couple Sync | `pairAndMessagingFlow_membersCanChatSendSnapsAndStoriesRealtime` | Pairs users and streams msgs/snaps/stories/notifs | Verified realtime streams on live Emulator | **PASS** |
| **TC-140** | **F17** Cloud Call Signaling | `discordMultiStreamCallRoom_participantsAndSignalsScopedAndRealtime` | Publishes cam+screen state, `SCREEN` offer, ends room | Verified live Emulator call state & signal | **PASS** |
| **TC-141** | **F20** Cloud Cross-User Block | `crossUserAccess_unauthorizedUserRejectedWithPermissionDenied` | Third party Eve receives `PERMISSION_DENIED` | Rejected read on user & room with `PERMISSION_DENIED` | **PASS** |
| **TC-142** | **F16** Cloud Notification Auth | `unauthenticatedCaller_observeNotificationsRejectedWithPermissionDenied` | Unauthenticated listener denied cleanly | Rejected with `PERMISSION_DENIED` | **PASS** |
| **TC-143** | **F01/F05/F07** Compose Welcome UI | `welcome screen renders auth actions and navigates to login and signup` | Renders 3 buttons and navigates to Login/Signup | All assertions & clicks verified on Compose tree | **PASS** |
| **TC-144** | **F03/F04/F08** Compose Signup Lock | `signup ui flow creates user, displays sandbox otp hint, verifies, and locks tabs until paired` | Signs up -> OTP verifies -> shows Sanctuary Lock | UI text inputs, OTP hint, Sanctuary Lock displayed | **PASS** |
| **TC-145** | **F11/F17** Compose Chat + Video Call | `paired user sees soulmate sync banner, sends chat message, and opens discord video call room` | Sends chat UI message, starts call, toggles Focus & Mute, hangs up | Compact viewport `CallLobbyCard` + controls verified | **PASS** |
| **TC-146** | **Base** Unit Sanity | `addition_isCorrect` | `2 + 2 == 4` | Passed | **PASS** |
| **TC-147** | **F01** Brand Resource Check | `read string from context` | `R.string.app_name == "for∞ever♥︎"` | Passed | **PASS** |
| **TC-148** | **UI** Roborazzi Baseline | `greeting_screenshot` | Captures Compose Pixel 8 snapshot | Passed | **PASS** |
| **TC-149** | **F20** Rules Default Deny | `Unauthenticated user: denied read/write on users, couple_rooms, call_rooms` | Unauthenticated queries denied | Live Firestore Emulator denied all 3 ops | **PASS** |
| **TC-150** | **F20** Rules Role Escalation | `User creation: owner succeeds with role=user, attacker blocked from setting role=admin or reading partner PII` | Allows `role=user`; blocks `role=admin` & PII read | Live Emulator permitted owner & blocked escalation | **PASS** |
| **TC-151** | **F20** Rules Schema Enforcement | `Shadow Update & Ghost field blocked on users` | Rejects undeclared fields & oversized strings | Live Emulator rejected ghost field & >len string | **PASS** |
| **TC-152** | **F20** Rules Admin Claim Check | `Admin spoof with email_verified=false is rejected` | Blocks unverified admin email token | Live Emulator rejected `email_verified=false` | **PASS** |
| **TC-153** | **F11/F20** Rules Couple Isolation | `Paired couple can exchange messages while third party Eve is denied` | Couple exchanges msgs; Eve denied get/list/write | Live Emulator enforced member isolation | **PASS** |
| **TC-154** | **F17/F20** Rules Call Terminal Lock | `Call room enforces member access, terminal ended state lock, and P2P signal isolation` | Member writes cam+screen; Eve blocked; `ended` locks | Live Emulator enforced signal privacy & terminal lock | **PASS** |
| **TC-155** | **F16/F20** Rules Notification Gate | `Partner can send notification into user notifications subcollection only when sharing a valid coupleRoomId` | Partner allowed; Eve denied injection | Live Emulator allowed valid member & blocked Eve | **PASS** |
