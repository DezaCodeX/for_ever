package com.dezacodex.data

import com.dezacodex.base.FirestoreEmulatorTestBase
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.UUID

private inline fun <reified T : Throwable> assertFailsWithType(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("Expected ${T::class.java.simpleName} but got ${t::class.java.simpleName}: ${t.message}", t)
    }
    throw AssertionError("Expected ${T::class.java.simpleName} to be thrown, but completed normally")
}

class FirestoreRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun uniqueEmail(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().take(8)}@heartsync.test"

    @Test
    fun ensureUserDocument_authenticatedUser_createsProfileAndInvite() = runBlocking {
        val aliceEmail = uniqueEmail("alice")
        val aliceUid = signInTestUser(aliceEmail, "Alice")
        val repo = FirestoreHeartRepository(firestore, auth)

        val user = withTimeout(TIMEOUT_MS) {
            repo.ensureUserDocument(auth.currentUser!!).getOrThrow()
        }
        assertThat(user.uid).isEqualTo(aliceUid)
        assertThat(user.inviteCode).startsWith("HS-")
        assertThat(user.role).isEqualTo("user")
    }

    @Test
    fun pairAndMessagingFlow_membersCanChatSendSnapsAndStoriesRealtime() = runBlocking {
        val aliceEmail = uniqueEmail("alice")
        val bobEmail = uniqueEmail("bob")

        val aliceUid = signInTestUser(aliceEmail, "Alice")
        val aliceRepo = FirestoreHeartRepository(firestore, auth)
        val aliceProfile = withTimeout(TIMEOUT_MS) {
            aliceRepo.ensureUserDocument(auth.currentUser!!).getOrThrow()
        }

        val bobUid = signInTestUser(bobEmail, "Bob")
        val bobRepo = FirestoreHeartRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            bobRepo.ensureUserDocument(auth.currentUser!!).getOrThrow()
        }

        val room = withTimeout(TIMEOUT_MS) {
            bobRepo.pairWithInviteCode(aliceProfile.inviteCode).getOrThrow()
        }
        assertThat(room.memberUids).containsExactly(aliceUid, bobUid)

        val members = listOf(aliceUid, bobUid)
        val msgId = withTimeout(TIMEOUT_MS) {
            bobRepo.sendMessage(
                roomId = room.roomId,
                memberUids = members,
                senderName = "Bob",
                text = "Hello Alice from Firebase!",
                isDisappearing = false
            ).getOrThrow()
        }

        val emittedMsgs = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeMessages(room.roomId).first { list -> list.any { it.id == msgId } }
        }
        assertThat(emittedMsgs.first { it.id == msgId }.text).isEqualTo("Hello Alice from Firebase!")

        withTimeout(TIMEOUT_MS) {
            bobRepo.reactToMessage(room.roomId, msgId, "❤️").getOrThrow()
        }

        val snapId = withTimeout(TIMEOUT_MS) {
            bobRepo.sendSnap(
                roomId = room.roomId,
                memberUids = members,
                senderName = "Bob",
                snapType = "IMAGE",
                description = "Coffee snap",
                mediaUrl = "https://firebasestorage.googleapis.com/v0/b/test/o/coffee.jpg",
                durationSec = 10
            ).getOrThrow()
        }
        val snaps = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeSnaps(room.roomId).first { list -> list.any { it.id == snapId } }
        }
        assertThat(snaps).isNotEmpty()

        val storyId = withTimeout(TIMEOUT_MS) {
            bobRepo.sendStory(
                roomId = room.roomId,
                memberUids = members,
                senderName = "Bob",
                description = "Sunset walk",
                imageType = "ROMANCE",
                mediaUrl = null
            ).getOrThrow()
        }
        val stories = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeStories(room.roomId).first { list -> list.any { it.id == storyId } }
        }
        assertThat(stories.map { it.id }).contains(storyId)

        val notifId = withTimeout(TIMEOUT_MS) {
            bobRepo.pushNotificationToUser(
                recipientUid = aliceUid,
                coupleRoomId = room.roomId,
                senderName = "Bob",
                type = "STREAK",
                title = "Streak Updated!",
                body = "Day 1 streak active"
            ).getOrThrow()
        }
        assertThat(notifId).isNotEmpty()

        // Switch back to Alice and verify she sees the pushed streak notification
        signInTestUser(aliceEmail)
        val aliceNotifs = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeNotifications(aliceUid).first { list -> list.any { it.id == notifId } }
        }
        assertThat(aliceNotifs.first { it.id == notifId }.type).isEqualTo("STREAK")
    }

    @Test
    fun discordMultiStreamCallRoom_participantsAndSignalsScopedAndRealtime() = runBlocking {
        val aliceUid = signInTestUser(uniqueEmail("alice_call"), "Alice")
        val bobUid = signInTestUser(uniqueEmail("bob_call"), "Bob")

        signInTestUser(uniqueEmail("alice_host"), "AliceHost").let { hostUid ->
            val hostRepo = FirestoreHeartRepository(firestore, auth)
            val callRoom = withTimeout(TIMEOUT_MS) {
                hostRepo.createCallRoom(
                    coupleRoomId = "room_${UUID.randomUUID().toString().take(8)}",
                    memberUids = listOf(hostUid, bobUid),
                    callType = "VIDEO",
                    title = "Discord Video Lounge"
                ).getOrThrow()
            }

            withTimeout(TIMEOUT_MS) {
                hostRepo.upsertCallParticipant(
                    FirestoreCallParticipant(
                        uid = hostUid,
                        callRoomId = callRoom.roomId,
                        memberUids = listOf(hostUid, bobUid),
                        displayName = "AliceHost",
                        isAudioMuted = false,
                        isCameraEnabled = true,
                        isScreenSharing = true,
                        screenAudioEnabled = true,
                        isSpeaking = true,
                        audioLevelDb = -18.0,
                        videoQuality = "simulcast"
                    )
                ).getOrThrow()
            }

            val participants = withTimeout(FLOW_TIMEOUT_MS) {
                hostRepo.observeCallParticipants(callRoom.roomId).first { it.any { p -> p.uid == hostUid } }
            }
            val localP = participants.first { it.uid == hostUid }
            assertThat(localP.isCameraEnabled).isTrue()
            assertThat(localP.isScreenSharing).isTrue()

            val signalId = "sig_${UUID.randomUUID().toString().take(8)}"
            withTimeout(TIMEOUT_MS) {
                hostRepo.sendCallSignal(
                    FirestoreCallSignal(
                        id = signalId,
                        callRoomId = callRoom.roomId,
                        fromUid = hostUid,
                        toUid = bobUid,
                        participantUids = listOf(hostUid, bobUid),
                        streamKind = "SCREEN",
                        signalType = "OFFER",
                        sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
                    )
                ).getOrThrow()
            }

            withTimeout(TIMEOUT_MS) {
                hostRepo.endCallRoom(callRoom.roomId).getOrThrow()
            }
        }
    }

    @Test
    fun crossUserAccess_unauthorizedUserRejectedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(uniqueEmail("alice_sec"), "AliceSec")
        val aliceRepo = FirestoreHeartRepository(firestore, auth)
        val aliceProfile = withTimeout(TIMEOUT_MS) {
            aliceRepo.ensureUserDocument(auth.currentUser!!).getOrThrow()
        }

        val bobUid = signInTestUser(uniqueEmail("bob_sec"), "BobSec")
        val bobRepo = FirestoreHeartRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            bobRepo.ensureUserDocument(auth.currentUser!!).getOrThrow()
        }
        val room = withTimeout(TIMEOUT_MS) {
            bobRepo.pairWithInviteCode(aliceProfile.inviteCode).getOrThrow()
        }

        // Eve tries to read Alice's user document and couple room
        signInTestUser(uniqueEmail("eve_sec"), "EveSec")
        val userReadErr = assertFailsWithType<FirebaseFirestoreException> {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid).get().await()
            }
        }
        assertThat(userReadErr.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)

        val roomReadErr = assertFailsWithType<FirebaseFirestoreException> {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("couple_rooms").document(room.roomId).get().await()
            }
        }
        assertThat(roomReadErr.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    @Test
    fun unauthenticatedCaller_observeNotificationsRejectedWithPermissionDenied() = runBlocking {
        val targetUid = "user_${UUID.randomUUID().toString().take(8)}"
        auth.signOut()
        val repo = FirestoreHeartRepository(firestore, auth)

        val exception = assertFailsWithType<Exception> {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeNotifications(targetUid).first()
            }
        }
        val firestoreException = checkNotNull(exception.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $exception"
        }
        assertThat(firestoreException.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
