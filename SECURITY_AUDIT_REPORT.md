# Application Security Audit Report — `for∞ever♥︎` (`HeartSync`)

## 1. Executive Security Summary

A full-stack application security audit was conducted across authentication, cryptography, local SQLite storage, Cloud Firestore security rules, Firebase Storage upload pipelines, WebRTC media/signaling, Android manifest permissions, logging hygiene, and the release updater.

| Domain | Security Posture | Key Controls Verified |
| :--- | :--- | :--- |
| **Password Storage** | **HARDENED** | `PBKDF2WithHmacSHA256`, **65,536 iterations**, 128-bit (`16-byte`) `SecureRandom` salt, 256-bit derived key, constant-time `MessageDigest.isEqual()` comparison, automatic transparent upgrade of legacy hashes on login |
| **OTP & Invite Entropy** | **HARDENED** | `java.security.SecureRandom` for 6-digit verification/pairing OTPs (5-minute TTL) and `HS-XXXX` invite codes; zero `kotlin.random.Random` usage for security tokens |
| **SQL Injection (SQLi)** | **IMMUNE** | 100% of Room `@Query` operations use compiled SQLite bound parameters (`:email`, `:code`, `:myEmail`, `:pEmail`); SQLi payloads (`' OR '1'='1`, `DROP TABLE`) are treated strictly as literal strings |
| **Input & Control-Char Sanitization** | **HARDENED** | `InputValidator.sanitizeDisplayText` strips null bytes (`\u0000`) and ISO control characters (`< 0x20` except `\n`/`\t`), enforces strict field length caps, and validates email/age (`13..120`) bounds |
| **Account Takeover & Admin Spoofing** | **HARDENED** | `triggerGoogleSignIn` blocks unverified sign-ins for `ADMIN_EMAILS` (`dezacodex@gmail.com`, `rdccpscollege@gmail.com`) and blocks overwriting existing password-protected accounts |
| **Firestore Zero-Trust Rules** | **HARDENED** | Default-deny (`allow read, write: if false;`), verified `email_verified == true` admin check, parent `couple_rooms`/`call_rooms` `get()` membership gates, strict schema/key allowlists (`hasAll`/`hasOnly`), immutable ownership fields, and terminal `status == 'ended'` lock on call rooms |
| **Storage Upload Validation** | **HARDENED** | Strict MIME whitelist (`image/*`, `video/*`) and 15 MB payload size ceiling enforced prior to Firebase Storage upload |
| **Play-Compliant Updater** | **HARDENED** | Removed raw APK sideloading / `REQUEST_INSTALL_PACKAGES`; uses HTTPS GitHub Releases API + `Intent.ACTION_VIEW` browser handoff with exact integer-tuple SemVer verification |
| **Secret & Log Hygiene** | **HARDENED** | `.env.example` contains only `UNCONFIGURED` non-secret sentinels; `WebSocketSignalingClient` and `FirestoreErrorHandler` never log passwords, tokens, TURN credentials, or raw SDP secrets |

---

## 2. Detailed Vulnerability Findings & Remediations

### 2.1 Cryptographic Password Hashing & Legacy Migration (`PasswordHasher.kt`, `HeartRepository.kt`)
- **Finding**: Earlier builds supported lower-iteration/legacy `salt:hash` formats and pre-validation login queries that did not enforce minimum password length prior to database lookup.
- **Remediation**:
  - Standardized `PasswordHasher.hashPassword` on **`pbkdf2$65536$<base64Salt>$<base64Hash>`** (`PBKDF2WithHmacSHA256`, 65,536 iterations, 16-byte `SecureRandom` salt, 256-bit key).
  - Maintained backward-compatible verification for legacy `salt:hash` records and automatically re-hashes and upgrades legacy records to `pbkdf2$65536$...` upon successful authentication.
  - Enforced constant-time `MessageDigest.isEqual` byte array comparison to prevent timing side-channel attacks.

### 2.2 Federated Auth Account-Takeover & Admin Privilege Escalation (`HeartViewModel.kt`, `firestore.rules`)
- **Finding**: Fallback/unverified Google sign-in helpers must never allow an attacker to type an admin email (`dezacodex@gmail.com` or `rdccpscollege@gmail.com`) or an existing password-registered user's email to hijack their local profile.
- **Remediation**:
  - `HeartViewModel.triggerGoogleSignIn` explicitly rejects any email in `ADMIN_EMAILS` (`dezacodex@gmail.com`, `rdccpscollege@gmail.com`) and rejects any email already registered in `users_table` with a non-blank `passwordHash` where `isGoogleUser == false`.
  - In `firestore.rules`, `isAdmin()` requires `request.auth.token.email_verified == true` in addition to matching the admin email allowlist (`dezacodex@gmail.com` or `rdccpscollege@gmail.com`), and `/users/{userId}` `create`/`update` blocks client-side `role == 'admin'` self-assignment.

### 2.3 Cross-Couple Data Isolation (`HeartDao.kt`, `HeartRepository.kt`, `firestore.rules`)
- **Finding**: Global `DELETE FROM love_messages` on "Clear Chat" risked deleting another local sandbox account's messages on shared devices; cloud notifications required couple-membership validation to prevent cross-user notification spam.
- **Remediation**:
  - Added `HeartDao.clearCoupleMessages(myEmail, pEmail)` so clearing chat while paired deletes only messages belonging to `(myEmail, pEmail)`.
  - In `firestore.rules`, `/couple_rooms/{roomId}` subcollections (`messages`, `snaps`, `stories`) verify parent room membership via `get()`, and `/users/{userId}/notifications/{notifId}` allows partner writes **only** when `isCoupleMember(incoming().coupleRoomId)` is satisfied and `type` is in `['MESSAGE', 'CALL', 'STREAK', 'SNAP', 'SCREENSHOT', 'STORY', 'PAIRING']`.

### 2.4 WebRTC Signaling & Media Privacy (`WebRtcCallEngine.kt`, `SignalingClient.kt`, `firestore.rules`)
- **Finding**: Call signaling channels must prevent third-party eavesdropping on SDP offers/answers/ICE candidates and prevent state tampering after call termination.
- **Remediation**:
  - `/call_rooms/{callRoomId}/signals/{signalId}` restricts `get`/`list`/`create` to verified `call_rooms` members where `auth.uid` is either `fromUid` or `toUid`.
  - Once a call room transitions to `status == 'ended'`, `firestore.rules` permanently locks the room and subcollections against further mutation.
  - When a user leaves a call or logs out, `WebRtcCallEngine.cleanupAllMedia()` and `DiscordCallController.hangUpCall()` immediately stop and dispose all `CameraVideoCapturer`, `ScreenCapturerAndroid`, `AudioRecord`, `AudioTrack`, and `VideoTrack` instances and stop foreground services (`CallMediaService`, `MediaProjectionService`).

### 2.5 AI / External Data Exfiltration Compliance
- Verified `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `app/src/main`: **zero** Gemini, Vertex AI, OpenAI, or third-party AI SDKs or endpoints exist in the application. No user messages, snaps, stories, or audio/video streams are transmitted to any AI service.
