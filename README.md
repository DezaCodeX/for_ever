# `for∞ever♥︎` (`HeartSync`) — Native Android App

A private couples messaging, ephemeral snaps, 24-hour moments, streak tracker, and Discord-style multi-stream WebRTC video & screen-sharing Android application built with Kotlin, Jetpack Compose, Room, Firebase (Auth, Firestore, Storage, Cloud Messaging), and `stream-webrtc-android`.

---

## 1. Local Build & Test

**Prerequisites**: Android Studio (or JDK 21 + Android SDK 36 + Gradle 9.3.1).

```bash
# Build debug APK (compiles cleanly offline or online)
gradle assembleDebug

# Run full unit + Compose UI + WebRTC test suite
gradle testDebugUnitTest --console=plain

# Run Firestore Security Rules tests (requires Firebase CLI & JDK)
firebase emulators:exec --only firestore --project demo-no-project "NODE_PATH=/usr/local/lib/node_modules node --test firestore.test.js"
```

---

## 2. Environment Variables (`.env`) & Where to Get Them

Create a `.env` file in the project root (copying from `.env.example`, do **not** commit `.env`):

```properties
SIGNALING_WS_URL=wss://your-signaling-server.example.com/ws
TURN_URL=turn:your-turn-server.example.com:3478
TURN_USER=your_turn_username
TURN_PASS=your_turn_password
```

- **Default STUN & Firebase Signaling**: Even if `.env` leaves these as `UNCONFIGURED`, the app compiles and connects peer discovery through Google STUN (`stun:stun.l.google.com:19302`) and Cloud Firestore `/call_rooms/{roomId}/signals` (when `app/google-services.json` is present).
- **`TURN_URL` / `TURN_USER` / `TURN_PASS`** (needed for symmetric carrier NAT traversal): Obtain from [Metered Open Relay / TURN](https://www.metered.ca/tools/openrelay/), [Cloudflare Calls TURN](https://developers.cloudflare.com/calls/turn/), or self-hosted `coturn`.
- **`SIGNALING_WS_URL`** (optional dedicated low-latency WebSocket signaling): Deploy any Node.js/Cloud Run WebSocket relay speaking `{ type: "join" | "offer" | "answer" | "ice-candidate" | "media-state" }`.

---

## 3. Firebase Setup (`involuted-oxygen-g5v9p`)

1. Open [Firebase Console](https://console.firebase.google.com/) -> project **`involuted-oxygen-g5v9p`** (region **`asia-south1`**).
2. Enable **Authentication** (Email/Password + Google Sign-In), **Cloud Firestore**, **Firebase Storage**, and **Cloud Messaging**.
3. Go to **Project Settings -> Your Apps -> Add Android App**:
   - **Android package name**: `com.aistudio.heartsync.lvwjzd`
   - **Debug SHA-1**: `D2:9B:C0:4D:62:4D:EF:5B:C0:4F:60:51:92:91:BD:F3:59:0A:7E:3B`
4. Download `google-services.json` and save it to **`app/google-services.json`**.
5. Deploy Firestore security rules:
   ```bash
   firebase deploy --only firestore:rules --project involuted-oxygen-g5v9p
   ```

---

## 4. Audit & Test Reports

- `APPLICATION_FEATURE_INVENTORY.md`
- `APPLICATION_AUDIT_REPORT.md`
- `LOGIN_TEST_REPORT.md`
- `VIDEO_CONFERENCING_TEST_REPORT.md`
- `SECURITY_AUDIT_REPORT.md`
- `PERFORMANCE_AUDIT_REPORT.md`
- `BUG_FIX_REPORT.md`
- `COMPLETE_TEST_CASES.md`
