package com.dezacodex.data

import com.dezacodex.security.SecurityUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Cloud Firestore repository for HeartSync.
 * Receives an explicitly initialized named-database FirebaseFirestore instance.
 */
class FirestoreHeartRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun currentUid(): String? = auth.currentUser?.uid

    suspend fun ensureUserDocument(firebaseUser: FirebaseUser): Result<FirestoreUser> {
        val uid = firebaseUser.uid
        val path = "users/$uid"
        return try {
            val docRef = firestore.collection("users").document(uid)
            val snap = docRef.get().await()
            if (snap.exists()) {
                val existing = snap.toObject(FirestoreUser::class.java)
                    ?: throw IllegalStateException("Failed to deserialize user profile")
                Result.success(existing)
            } else {
                val inviteCode = SecurityUtils.generateInviteCode()
                val displayName = firebaseUser.displayName?.takeIf { it.isNotBlank() }
                    ?: firebaseUser.email?.substringBefore("@")
                    ?: "HeartSync User"
                val newUser = FirestoreUser(
                    id = uid,
                    uid = uid,
                    email = firebaseUser.email?.lowercase() ?: "$uid@heartsync.user",
                    username = displayName.take(60),
                    photoUrl = firebaseUser.photoUrl?.toString(),
                    inviteCode = inviteCode,
                    role = "user"
                )
                val batch = firestore.batch()
                batch.set(docRef, newUser.toCreateMap())
                val inviteRef = firestore.collection("pair_invites").document(inviteCode)
                batch.set(
                    inviteRef,
                    mapOf(
                        "inviteCode" to inviteCode,
                        "ownerUid" to uid,
                        "ownerName" to newUser.username,
                        "isClaimed" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.commit().await()
                Result.success(newUser)
            }
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, path, auth)
            Result.failure(e)
        }
    }

    fun observeUser(uid: String): Flow<FirestoreUser?> {
        return firestore.collection("users").document(uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(FirestoreUser::class.java)
            }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(err, OperationType.GET, "users/$uid", auth)
                throw err
            }
    }

    suspend fun updateUserFields(uid: String, fields: Map<String, Any>): Result<Unit> {
        val path = "users/$uid"
        return try {
            val payload = fields.toMutableMap()
            payload["updatedAt"] = FieldValue.serverTimestamp()
            firestore.collection("users").document(uid)
                .update(payload)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateFcmToken(uid: String, token: String): Result<Unit> {
        return updateUserFields(uid, mapOf("fcmToken" to token.take(512)))
    }

    /**
     * Pairs two users via /pair_invites/{inviteCode} and creates a shared /couple_rooms/{roomId}.
     */
    suspend fun pairWithInviteCode(inviteCode: String): Result<FirestoreCoupleRoom> {
        val myUid = currentUid() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val normalized = inviteCode.trim().uppercase()
        val invitePath = "pair_invites/$normalized"
        return try {
            val myUserSnap = firestore.collection("users").document(myUid).get().await()
            val myUser = myUserSnap.toObject(FirestoreUser::class.java)
                ?: throw IllegalStateException("Profile not initialized")

            val inviteSnap = firestore.collection("pair_invites").document(normalized).get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(IllegalArgumentException("Invite code not found."))
            }
            val ownerUid = inviteSnap.getString("ownerUid") ?: ""
            val ownerName = inviteSnap.getString("ownerName") ?: "Partner"
            val isClaimed = inviteSnap.getBoolean("isClaimed") ?: false
            if (ownerUid == myUid) {
                return Result.failure(IllegalArgumentException("You cannot pair with your own code."))
            }
            if (isClaimed) {
                return Result.failure(IllegalStateException("Invite code has already been claimed."))
            }

            val roomId = listOf(ownerUid, myUid).sorted().joinToString("_")
            val room = FirestoreCoupleRoom(
                id = roomId,
                roomId = roomId,
                ownerUid = myUid,
                memberUids = listOf(ownerUid, myUid),
                myName = myUser.username,
                partnerName = ownerName,
                statusText = "Holding hands forever & always",
                anniversaryDate = myUser.anniversaryDate,
                streakCount = 1,
                totalScore = 15
            )

            val batch = firestore.batch()
            batch.set(firestore.collection("couple_rooms").document(roomId), room.toCreateMap())
            batch.update(
                firestore.collection("pair_invites").document(normalized),
                mapOf(
                    "isClaimed" to true,
                    "claimedByUid" to myUid,
                    "coupleRoomId" to roomId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                firestore.collection("users").document(myUid),
                mapOf(
                    "partnerUid" to ownerUid,
                    "partnerUsername" to ownerName,
                    "coupleRoomId" to roomId,
                    "streakCount" to 1,
                    "snapScore" to 15,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(room)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, invitePath, auth)
            Result.failure(e)
        }
    }

    fun observeCoupleRoomsForCurrentUser(): Flow<List<FirestoreCoupleRoom>> {
        val uid = currentUid() ?: throw IllegalStateException("Not authenticated")
        return firestore.collection("couple_rooms")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toObject(FirestoreCoupleRoom::class.java) }
            }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(err, OperationType.LIST, "couple_rooms", auth)
                throw err
            }
    }

    fun observeMessages(roomId: String): Flow<List<FirestoreCoupleMessage>> {
        val uid = currentUid() ?: throw IllegalStateException("Not authenticated")
        return firestore.collection("couple_rooms").document(roomId)
            .collection("messages")
            .whereArrayContains("memberUids", uid)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snap ->
                val now = System.currentTimeMillis()
                snap.documents.mapNotNull { doc ->
                    doc.toObject(FirestoreCoupleMessage::class.java)
                }.filter { msg ->
                    !msg.isDisappearing || msg.isSavedInChat || msg.expiresAtMs == null || msg.expiresAtMs > now
                }
            }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(
                    err,
                    OperationType.LIST,
                    "couple_rooms/$roomId/messages",
                    auth
                )
                throw err
            }
    }

    suspend fun sendMessage(
        roomId: String,
        memberUids: List<String>,
        senderName: String,
        text: String,
        mediaUrl: String? = null,
        isDisappearing: Boolean = false,
        durationSecs: Int = 10,
        msgEffect: String? = null
    ): Result<String> {
        val uid = currentUid() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val msgId = UUID.randomUUID().toString()
        val path = "couple_rooms/$roomId/messages/$msgId"
        return try {
            val expiresAtMs = if (isDisappearing) System.currentTimeMillis() + (durationSecs * 1000L) else null
            val msg = FirestoreCoupleMessage(
                docId = msgId,
                id = msgId,
                roomId = roomId,
                senderUid = uid,
                senderName = senderName.take(60),
                memberUids = memberUids,
                text = text.take(2000),
                mediaUrl = mediaUrl,
                isDisappearing = isDisappearing,
                durationSecs = if (isDisappearing) durationSecs else null,
                expiresAtMs = expiresAtMs,
                viewed = false,
                isSavedInChat = false,
                msgEffect = msgEffect?.takeIf { it.isNotBlank() }
            )
            firestore.collection("couple_rooms").document(roomId)
                .collection("messages").document(msgId)
                .set(msg.toCreateMap())
                .await()
            Result.success(msgId)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun reactToMessage(roomId: String, msgId: String, reaction: String): Result<Unit> {
        val path = "couple_rooms/$roomId/messages/$msgId"
        return try {
            firestore.collection("couple_rooms").document(roomId)
                .collection("messages").document(msgId)
                .update(mapOf("reaction" to reaction.take(16), "updatedAt" to FieldValue.serverTimestamp()))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun sendSnap(
        roomId: String,
        memberUids: List<String>,
        senderName: String,
        snapType: String,
        description: String,
        mediaUrl: String?,
        durationSec: Int
    ): Result<String> {
        val uid = currentUid() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val snapId = UUID.randomUUID().toString()
        val path = "couple_rooms/$roomId/snaps/$snapId"
        return try {
            val snap = FirestoreCoupleSnap(
                docId = snapId,
                id = snapId,
                roomId = roomId,
                senderUid = uid,
                senderName = senderName.take(60),
                memberUids = memberUids,
                snapType = if (snapType == "VIDEO") "VIDEO" else "IMAGE",
                description = description.take(500),
                mediaUrl = mediaUrl,
                durationSec = durationSec.coerceIn(1, 120),
                viewed = false,
                screenshotAlert = false,
                isSavedInChat = false
            )
            firestore.collection("couple_rooms").document(roomId)
                .collection("snaps").document(snapId)
                .set(snap.toCreateMap())
                .await()
            Result.success(snapId)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, path, auth)
            Result.failure(e)
        }
    }

    fun observeSnaps(roomId: String): Flow<List<FirestoreCoupleSnap>> {
        val uid = currentUid() ?: throw IllegalStateException("Not authenticated")
        return firestore.collection("couple_rooms").document(roomId)
            .collection("snaps")
            .whereArrayContains("memberUids", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap -> snap.documents.mapNotNull { it.toObject(FirestoreCoupleSnap::class.java) } }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(err, OperationType.LIST, "couple_rooms/$roomId/snaps", auth)
                throw err
            }
    }

    suspend fun sendStory(
        roomId: String,
        memberUids: List<String>,
        senderName: String,
        description: String,
        imageType: String,
        mediaUrl: String?
    ): Result<String> {
        val uid = currentUid() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val storyId = UUID.randomUUID().toString()
        val path = "couple_rooms/$roomId/stories/$storyId"
        return try {
            val story = FirestoreCoupleStory(
                docId = storyId,
                id = storyId,
                roomId = roomId,
                senderUid = uid,
                senderName = senderName.take(60),
                memberUids = memberUids,
                description = description.take(500),
                imageType = imageType.take(32),
                mediaUrl = mediaUrl,
                expiresAtMs = System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
                isSavedInChat = false
            )
            firestore.collection("couple_rooms").document(roomId)
                .collection("stories").document(storyId)
                .set(story.toCreateMap())
                .await()
            Result.success(storyId)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, path, auth)
            Result.failure(e)
        }
    }

    fun observeStories(roomId: String): Flow<List<FirestoreCoupleStory>> {
        val uid = currentUid() ?: throw IllegalStateException("Not authenticated")
        return firestore.collection("couple_rooms").document(roomId)
            .collection("stories")
            .whereArrayContains("memberUids", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap ->
                val now = System.currentTimeMillis()
                snap.documents.mapNotNull { it.toObject(FirestoreCoupleStory::class.java) }
                    .filter { it.expiresAtMs > now || it.isSavedInChat }
            }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(err, OperationType.LIST, "couple_rooms/$roomId/stories", auth)
                throw err
            }
    }

    // ---------------------------------------------------------
    // USER-SCOPED NOTIFICATIONS (MESSAGES, CALLS, STREAKS, SNAPS)
    // ---------------------------------------------------------
    suspend fun pushNotificationToUser(
        recipientUid: String,
        coupleRoomId: String,
        senderName: String,
        type: String,
        title: String,
        body: String
    ): Result<String> {
        val uid = currentUid() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val notifId = UUID.randomUUID().toString()
        val path = "users/$recipientUid/notifications/$notifId"
        return try {
            val notif = FirestoreUserNotification(
                docId = notifId,
                id = notifId,
                senderUid = uid,
                senderName = senderName.take(60),
                coupleRoomId = coupleRoomId,
                type = type,
                title = title.take(120),
                body = body.take(500),
                isRead = false
            )
            firestore.collection("users").document(recipientUid)
                .collection("notifications").document(notifId)
                .set(notif.toCreateMap())
                .await()
            Result.success(notifId)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, path, auth)
            Result.failure(e)
        }
    }

    fun observeNotifications(uid: String): Flow<List<FirestoreUserNotification>> {
        return firestore.collection("users").document(uid)
            .collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap -> snap.documents.mapNotNull { it.toObject(FirestoreUserNotification::class.java) } }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(err, OperationType.LIST, "users/$uid/notifications", auth)
                throw err
            }
    }

    suspend fun markNotificationRead(uid: String, notificationId: String): Result<Unit> {
        val path = "users/$uid/notifications/$notificationId"
        return try {
            firestore.collection("users").document(uid)
                .collection("notifications").document(notificationId)
                .update(mapOf("isRead" to true, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun markAllNotificationsRead(uid: String, notificationIds: List<String>): Result<Unit> {
        if (notificationIds.isEmpty()) return Result.success(Unit)
        val path = "users/$uid/notifications"
        return try {
            val batch = firestore.batch()
            val col = firestore.collection("users").document(uid).collection("notifications")
            notificationIds.take(100).forEach { id ->
                batch.update(col.document(id), mapOf("isRead" to true, "updatedAt" to FieldValue.serverTimestamp()))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, path, auth)
            Result.failure(e)
        }
    }

    suspend fun syncCoupleStreakAndScore(
        roomId: String,
        uid: String,
        streakCount: Int,
        totalScore: Int
    ): Result<Unit> {
        val path = "couple_rooms/$roomId"
        return try {
            val batch = firestore.batch()
            batch.update(
                firestore.collection("couple_rooms").document(roomId),
                mapOf(
                    "streakCount" to streakCount.coerceAtLeast(0),
                    "totalScore" to totalScore.coerceAtLeast(0),
                    "lastInteractionAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                firestore.collection("users").document(uid),
                mapOf(
                    "streakCount" to streakCount.coerceAtLeast(0),
                    "snapScore" to totalScore.coerceAtLeast(0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, path, auth)
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------
    // DISCORD-STYLE CALL ROOMS, PARTICIPANTS & WEBRTC SIGNALS
    // ---------------------------------------------------------
    suspend fun createCallRoom(
        coupleRoomId: String?,
        memberUids: List<String>,
        callType: String,
        title: String
    ): Result<FirestoreCallRoom> {
        val uid = currentUid() ?: return Result.failure(IllegalStateException("Not authenticated"))
        val roomId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val normalizedMembers = (memberUids + uid).distinct().take(16)
        val room = FirestoreCallRoom(
            docId = roomId,
            roomId = roomId,
            coupleRoomId = coupleRoomId,
            hostUid = uid,
            title = title.take(80),
            callType = if (callType == "VOICE") "VOICE" else "VIDEO",
            status = "active",
            layoutMode = "GRID",
            autoCamSwitch = true,
            hideNonVideo = false,
            memberUids = normalizedMembers
        )
        return try {
            firestore.collection("call_rooms").document(roomId)
                .set(room.toCreateMap())
                .await()
            Result.success(room)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, "call_rooms/$roomId", auth)
            Result.failure(e)
        }
    }

    suspend fun upsertCallParticipant(participant: FirestoreCallParticipant): Result<Unit> {
        val path = "call_rooms/${participant.callRoomId}/participants/${participant.uid}"
        return try {
            val ref = firestore.collection("call_rooms").document(participant.callRoomId)
                .collection("participants").document(participant.uid)
            val snap = ref.get().await()
            if (!snap.exists()) {
                ref.set(participant.toCreateMap()).await()
            } else {
                ref.update(
                    mapOf(
                        "displayName" to participant.displayName.take(60),
                        "isAudioMuted" to participant.isAudioMuted,
                        "isCameraEnabled" to participant.isCameraEnabled,
                        "isScreenSharing" to participant.isScreenSharing,
                        "screenAudioEnabled" to participant.screenAudioEnabled,
                        "isSpeaking" to participant.isSpeaking,
                        "audioLevelDb" to participant.audioLevelDb,
                        "videoQuality" to participant.videoQuality,
                        "memberUids" to participant.memberUids,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, path, auth)
            Result.failure(e)
        }
    }

    fun observeCallParticipants(callRoomId: String): Flow<List<FirestoreCallParticipant>> {
        val uid = currentUid() ?: throw IllegalStateException("Not authenticated")
        return firestore.collection("call_rooms").document(callRoomId)
            .collection("participants")
            .whereArrayContains("memberUids", uid)
            .snapshots(com.google.firebase.firestore.MetadataChanges.EXCLUDE)
            .map { snap ->
                snap.documents.mapNotNull { it.toObject(FirestoreCallParticipant::class.java) }
            }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(
                    err,
                    OperationType.LIST,
                    "call_rooms/$callRoomId/participants",
                    auth
                )
                throw err
            }
    }

    suspend fun sendCallSignal(signal: FirestoreCallSignal): Result<String> {
        val path = "call_rooms/${signal.callRoomId}/signals/${signal.id}"
        return try {
            firestore.collection("call_rooms").document(signal.callRoomId)
                .collection("signals").document(signal.id)
                .set(signal.toCreateMap())
                .await()
            Result.success(signal.id)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, path, auth)
            Result.failure(e)
        }
    }

    fun observeIncomingSignals(callRoomId: String): Flow<List<FirestoreCallSignal>> {
        val uid = currentUid() ?: throw IllegalStateException("Not authenticated")
        return firestore.collection("call_rooms").document(callRoomId)
            .collection("signals")
            .whereArrayContains("participantUids", uid)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toObject(FirestoreCallSignal::class.java) }
                    .filter { it.toUid == uid }
            }
            .catch { err ->
                FirestoreErrorHandler.handleFirestoreError(
                    err,
                    OperationType.LIST,
                    "call_rooms/$callRoomId/signals",
                    auth
                )
                throw err
            }
    }

    suspend fun endCallRoom(callRoomId: String): Result<Unit> {
        val path = "call_rooms/$callRoomId"
        return try {
            firestore.collection("call_rooms").document(callRoomId)
                .update(
                    mapOf(
                        "status" to "ended",
                        "endedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, path, auth)
            Result.failure(e)
        }
    }
}
