package com.dezacodex.audit

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.data.FirebaseStorageRepository
import com.dezacodex.data.HeartDao
import com.dezacodex.data.HeartDatabase
import com.dezacodex.data.HeartRepository
import com.dezacodex.data.LoveMessage
import com.dezacodex.data.LoversProfile
import com.dezacodex.notifications.HeartNotificationHelper
import com.dezacodex.updater.UpdateManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeatureAuditUnitTest {

    private lateinit var context: Context
    private lateinit var db: HeartDatabase
    private lateinit var dao: HeartDao
    private lateinit var repo: HeartRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HeartDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.heartDao()
        repo = HeartRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------
    // FEATURE 1: Couple Pairing & Invite Linking (5 Tests)
    // ---------------------------------------------------------------
    @Test
    fun `pairing linkCouple pairs two users, sets streaks to at least 1, and adds 15 score`() = runBlocking {
        repo.ensureProfileExists()
        val beforeScore = dao.getProfileDirect()!!.totalScore
        val alice = repo.registerUser("alice@hs.test", "Alice", "Password123")
        val bob = repo.registerUser("bob@hs.test", "Bob", "Password123")

        val linked = repo.linkCouple("alice@hs.test", bob.inviteCode)
        assertThat(linked).isTrue()

        val updatedAlice = dao.getUserByEmail("alice@hs.test")!!
        val updatedBob = dao.getUserByEmail("bob@hs.test")!!
        val profile = dao.getProfileDirect()!!
        assertThat(updatedAlice.connectedPartnerEmail).isEqualTo("bob@hs.test")
        assertThat(updatedBob.connectedPartnerEmail).isEqualTo("alice@hs.test")
        assertThat(updatedAlice.streakCount).isAtLeast(1)
        assertThat(profile.totalScore).isEqualTo(beforeScore + 15)
    }

    @Test
    fun `pairing linkCouple rejects self pairing with own invite code`() = runBlocking {
        val alice = repo.registerUser("alice@hs.test", "Alice", "Password123")
        assertThat(repo.linkCouple("alice@hs.test", alice.inviteCode)).isFalse()
    }

    @Test
    fun `pairing linkCouple rejects already paired target user`() = runBlocking {
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        val bob = repo.registerUser("bob@hs.test", "Bob", "Password123")
        repo.registerUser("charlie@hs.test", "Charlie", "Password123")

        assertThat(repo.linkCouple("alice@hs.test", bob.inviteCode)).isTrue()
        assertThat(repo.linkCouple("charlie@hs.test", bob.inviteCode)).isFalse()
    }

    @Test
    fun `pairing removeConnection clears partner reference on both sides`() = runBlocking {
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        val bob = repo.registerUser("bob@hs.test", "Bob", "Password123")
        repo.linkCouple("alice@hs.test", bob.inviteCode)

        repo.removeConnection("alice@hs.test")
        assertThat(dao.getUserByEmail("alice@hs.test")!!.connectedPartnerEmail).isNull()
        assertThat(dao.getUserByEmail("bob@hs.test")!!.connectedPartnerEmail).isNull()
    }

    @Test
    fun `pairing getUserByInviteCode normalizes case and whitespace`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Password123")
        val found = repo.getUserByInviteCode("  ${bob.inviteCode.lowercase()}  ")
        assertThat(found?.email).isEqualTo("bob@hs.test")
    }

    // ---------------------------------------------------------------
    // FEATURE 2: Private Chat, Reactions, Save-in-Chat & Expiry (6 Tests)
    // ---------------------------------------------------------------
    @Test
    fun `chat sendMessage persists message for couple and increments snap scores`() = runBlocking {
        repo.ensureProfileExists()
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        repo.sendMessage("Alice", "Good morning!", senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")

        val msgs = repo.getMessagesForCouple("alice@hs.test", "bob@hs.test").first()
        assertThat(msgs).hasSize(1)
        assertThat(msgs[0].text).isEqualTo("Good morning!")
        assertThat(dao.getUserByEmail("alice@hs.test")!!.snapScore).isEqualTo(1)
    }

    @Test
    fun `chat getMessagesForCouple isolates conversation from third-party messages`(): Unit = runBlocking {
        repo.sendMessage("Alice", "Secret for Bob", senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        repo.sendMessage("Eve", "Unrelated", senderEmail = "eve@hs.test", receiverEmail = "mallory@hs.test")

        val msgs = repo.getMessagesForCouple("alice@hs.test", "bob@hs.test").first()
        assertThat(msgs.map { it.text }).containsExactly("Secret for Bob")
    }

    @Test
    fun `chat updateMessageReaction stores emoji reaction`() = runBlocking {
        repo.sendMessage("Alice", "Miss you", senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        val msg = dao.getAllMessagesDirect().first()
        repo.updateMessageReaction(msg.id, "❤️")

        assertThat(dao.getAllMessagesDirect().first().reaction).isEqualTo("❤️")
    }

    @Test
    fun `chat purgeExpiredDisappearingMessages deletes expired unsaved items but keeps saved ones`(): Unit = runBlocking {
        val now = 1_000_000L
        dao.insertMessage(
            LoveMessage(
                senderName = "Alice",
                senderEmail = "alice@hs.test",
                receiverEmail = "bob@hs.test",
                text = "Vanish soon",
                isDisappearing = true,
                durationSecs = 10,
                isSavedInChat = false,
                timestamp = now - 20_000L
            )
        )
        dao.insertMessage(
            LoveMessage(
                senderName = "Alice",
                senderEmail = "alice@hs.test",
                receiverEmail = "bob@hs.test",
                text = "Pinned memory",
                isDisappearing = true,
                durationSecs = 10,
                isSavedInChat = true,
                timestamp = now - 20_000L
            )
        )

        val deletedCount = repo.purgeExpiredDisappearingMessages(nowMs = now)
        assertThat(deletedCount).isEqualTo(1)
        val remaining = dao.getAllMessagesDirect()
        assertThat(remaining.map { it.text }).containsExactly("Pinned memory")
    }

    @Test
    fun `chat deleteMessage and clearChat remove messages properly`() = runBlocking {
        repo.sendMessage("Alice", "One", senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        repo.sendMessage("Alice", "Two", senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        val firstId = dao.getAllMessagesDirect().first().id

        repo.deleteMessage(firstId)
        assertThat(dao.getAllMessagesDirect()).hasSize(1)

        repo.clearChat()
        assertThat(dao.getAllMessagesDirect()).isEmpty()
    }

    // ---------------------------------------------------------------
    // FEATURE 3: Ephemeral Snaps & Screenshot Alerts (5 Tests)
    // ---------------------------------------------------------------
    @Test
    fun `snaps sendSnap awards 1 point for IMAGE and 2 points for VIDEO`() = runBlocking {
        repo.ensureProfileExists()
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        repo.sendSnap("Alice", "IMAGE", "Photo", durationSec = 10, senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        assertThat(dao.getUserByEmail("alice@hs.test")!!.snapScore).isEqualTo(1)

        repo.sendSnap("Alice", "VIDEO", "Clip", durationSec = 15, senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        assertThat(dao.getUserByEmail("alice@hs.test")!!.snapScore).isEqualTo(3)
    }

    @Test
    fun `snaps viewSnapAndTriggerStreak marks viewed and increments streak synced`() = runBlocking {
        dao.updateProfile(LoversProfile(streakCount = 0, lastInteractionTime = 0L))
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        repo.sendSnap("Bob", "IMAGE", "Morning", 10, "bob@hs.test", "alice@hs.test")
        val snap = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first()
        assertThat(snap.viewed).isFalse()

        repo.viewSnapAndTriggerStreak(snap.id, "alice@hs.test")
        val viewedSnap = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first()
        assertThat(viewedSnap.viewed).isTrue()
        assertThat(dao.getUserByEmail("alice@hs.test")!!.streakCount).isEqualTo(1)
        assertThat(dao.getProfileDirect()!!.streakCount).isEqualTo(1)
    }

    @Test
    fun `snaps alertScreenshot sets screenshotAlert flag on target snap`() = runBlocking {
        repo.sendSnap("Bob", "IMAGE", "Private", 10, "bob@hs.test", "alice@hs.test")
        val snap = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first()
        repo.alertScreenshot(snap.id)

        val updated = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first()
        assertThat(updated.screenshotAlert).isTrue()
    }

    @Test
    fun `snaps saveSnapToChat and downloadSnapToLocal update saved flags and paths`() = runBlocking {
        repo.sendSnap("Bob", "IMAGE", "Keep", 10, "bob@hs.test", "alice@hs.test")
        val snapId = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first().id
        repo.saveSnapToChat(snapId, true)
        repo.downloadSnapToLocal(snapId, "/downloads/snap_1.txt")

        val snap = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first()
        assertThat(snap.isSavedInChat).isTrue()
        assertThat(snap.localStoragePath).isEqualTo("/downloads/snap_1.txt")
    }

    @Test
    fun `snaps deleteSnap removes snap record`() = runBlocking {
        repo.sendSnap("Bob", "IMAGE", "Remove", 10, "bob@hs.test", "alice@hs.test")
        val snapId = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first().id
        repo.deleteSnap(snapId)
        assertThat(repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first()).isEmpty()
    }

    // ---------------------------------------------------------------
    // FEATURE 4: Couple Stories & Highlights (5 Tests)
    // ---------------------------------------------------------------
    @Test
    fun `stories shareStory awards 3 points and increments totalMomentsCount`() = runBlocking {
        repo.ensureProfileExists()
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        repo.shareStory("Alice", "Date night", "DATE_NIGHT", senderEmail = "alice@hs.test")

        val user = dao.getUserByEmail("alice@hs.test")!!
        assertThat(user.snapScore).isEqualTo(3)
        assertThat(user.totalMomentsCount).isEqualTo(1)
    }

    @Test
    fun `stories getStoriesForCouple scopes stories between couple members`(): Unit = runBlocking {
        val alice = repo.registerUser("alice@hs.test", "Alice", "Password123")
        val bob = repo.registerUser("bob@hs.test", "Bob", "Password123")
        repo.linkCouple(alice.email, bob.inviteCode)

        repo.shareStory("Alice", "Our picnic", "ROMANCE", senderEmail = "alice@hs.test")
        repo.shareStory("Eve", "Other picnic", "ROMANCE", senderEmail = "eve@hs.test")

        val coupleStories = repo.getStoriesForCouple("alice@hs.test", "bob@hs.test").first()
        assertThat(coupleStories.map { it.description }).containsExactly("Our picnic")
    }

    @Test
    fun `stories saveStoryToChat updates highlight state`() = runBlocking {
        repo.shareStory("Alice", "Anniversary", "MILESTONE", senderEmail = "alice@hs.test")
        val storyId = repo.allStories.first().first().id
        repo.saveStoryToChat(storyId, true)
        assertThat(repo.allStories.first().first().isSavedInChat).isTrue()
    }

    @Test
    fun `stories downloadStoryToLocal records export file path`() = runBlocking {
        repo.shareStory("Alice", "Export me", "ROMANCE", senderEmail = "alice@hs.test")
        val storyId = repo.allStories.first().first().id
        repo.downloadStoryToLocal(storyId, "/downloads/story_export.txt")
        assertThat(repo.allStories.first().first().localStoragePath).isEqualTo("/downloads/story_export.txt")
    }

    @Test
    fun `stories deleteStory deletes story from Room`() = runBlocking {
        repo.shareStory("Alice", "Delete me", "ROMANCE", senderEmail = "alice@hs.test")
        val storyId = repo.allStories.first().first().id
        repo.deleteStory(storyId)
        assertThat(repo.allStories.first()).isEmpty()
    }

    // ---------------------------------------------------------------
    // FEATURE 5: Streak Sync & Notifications (6 Tests)
    // ---------------------------------------------------------------
    @Test
    fun `streaks recordSnapStreakActivity increments streak on consecutive 24h window and syncs User`() = runBlocking {
        val now = System.currentTimeMillis()
        val thirtyHoursAgo = now - (30 * 60 * 60 * 1000L)
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        repo.updateProfile(
            LoversProfile(
                loggedInUserEmail = "alice@hs.test",
                streakCount = 4,
                totalScore = 40,
                lastInteractionTime = thirtyHoursAgo
            )
        )

        repo.recordSnapStreakActivity("alice@hs.test")
        val profile = dao.getProfileDirect()!!
        val user = dao.getUserByEmail("alice@hs.test")!!
        assertThat(profile.streakCount).isEqualTo(5)
        assertThat(user.streakCount).isEqualTo(5)
        assertThat(profile.totalScore).isEqualTo(41)
    }

    @Test
    fun `streaks resets to 1 when gap exceeds 48 hours`() = runBlocking {
        val now = System.currentTimeMillis()
        val sixtyHoursAgo = now - (60 * 60 * 60 * 1000L)
        repo.registerUser("alice@hs.test", "Alice", "Password123")
        repo.updateProfile(
            LoversProfile(
                loggedInUserEmail = "alice@hs.test",
                streakCount = 12,
                lastInteractionTime = sixtyHoursAgo
            )
        )

        repo.recordSnapStreakActivity("alice@hs.test")
        assertThat(dao.getProfileDirect()!!.streakCount).isEqualTo(1)
        assertThat(dao.getUserByEmail("alice@hs.test")!!.streakCount).isEqualTo(1)
    }

    @Test
    fun `notifications ensureChannelsCreated registers all 4 NotificationChannels on Android O+`() {
        HeartNotificationHelper.ensureChannelsCreated(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelIds = nm.notificationChannels.map { it.id }
            assertThat(channelIds).containsAtLeast(
                HeartNotificationHelper.CHANNEL_MESSAGES,
                HeartNotificationHelper.CHANNEL_CALLS,
                HeartNotificationHelper.CHANNEL_STREAKS,
                HeartNotificationHelper.CHANNEL_SNAPS
            )
        }
    }

    @Test
    fun `notifications channelForType maps CALL, STREAK, SNAP, SCREENSHOT, and MESSAGE`() {
        assertThat(HeartNotificationHelper.channelForType("CALL")).isEqualTo(HeartNotificationHelper.CHANNEL_CALLS)
        assertThat(HeartNotificationHelper.channelForType("STREAK")).isEqualTo(HeartNotificationHelper.CHANNEL_STREAKS)
        assertThat(HeartNotificationHelper.channelForType("SNAP")).isEqualTo(HeartNotificationHelper.CHANNEL_SNAPS)
        assertThat(HeartNotificationHelper.channelForType("SCREENSHOT")).isEqualTo(HeartNotificationHelper.CHANNEL_SNAPS)
        assertThat(HeartNotificationHelper.channelForType("MESSAGE")).isEqualTo(HeartNotificationHelper.CHANNEL_MESSAGES)
    }

    @Test
    fun `notifications showNotification gracefully handles permission state in test environment`() {
        val result = HeartNotificationHelper.showNotification(
            context = context,
            channelId = HeartNotificationHelper.CHANNEL_MESSAGES,
            title = "New Message",
            body = "Hi from partner",
            notificationId = 101
        )
        // Result matches exact runtime permission status (no crash either way)
        assertThat(result).isEqualTo(HeartNotificationHelper.hasNotificationPermission(context))
    }

    // ---------------------------------------------------------------
    // FEATURE 6: Profiles, Downloads, Play-Compliant Updater & Admin (5 Tests)
    // ---------------------------------------------------------------
    @Test
    fun `storage saveMediaToDeviceDownloads persists local artifact without crashing`() = runBlocking {
        val storageRepo = FirebaseStorageRepository(context)
        val res = storageRepo.saveMediaToDeviceDownloads(
            filename = "heartsync_test_export.txt",
            mediaUrl = null,
            fallbackTextSummary = "HeartSync Moment Export"
        )
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrThrow()).isNotEmpty()
    }

    @Test
    fun `updater openReleasePage invokes View intent cleanly in Play-compliant flow`() {
        val updater = UpdateManager(context)
        val launched = updater.openReleasePage("https://github.com/DezaCodex/HeartSync-App/releases/latest")
        assertThat(launched).isTrue()
    }

    @Test
    fun `admin getAllUsers and deleteUserAccount manage registered users cleanly`(): Unit = runBlocking {
        repo.registerUser("u1@hs.test", "U1", "Pass1234")
        repo.registerUser("u2@hs.test", "U2", "Pass1234")
        assertThat(repo.getAllUsers()).hasSize(2)

        repo.deleteUserAccount("u1@hs.test")
        assertThat(repo.getAllUsers().map { it.email }).containsExactly("u2@hs.test")
    }

    @Test
    fun `profiles updateProfile persists theme, tones, and anniversary changes`() = runBlocking {
        repo.ensureProfileExists()
        val p = dao.getProfileDirect()!!
        repo.updateProfile(
            p.copy(
                currentTheme = "Midnight Velvet",
                anniversaryDate = "2023-02-14",
                notificationTone = "Soft Chimes"
            )
        )
        val reloaded = dao.getProfileDirect()!!
        assertThat(reloaded.currentTheme).isEqualTo("Midnight Velvet")
        assertThat(reloaded.anniversaryDate).isEqualTo("2023-02-14")
        assertThat(reloaded.notificationTone).isEqualTo("Soft Chimes")
    }
}
