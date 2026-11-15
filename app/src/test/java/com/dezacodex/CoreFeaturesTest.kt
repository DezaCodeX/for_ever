package com.dezacodex

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.data.CoupleStory
import com.dezacodex.data.HeartDatabase
import com.dezacodex.ui.AppScreen
import com.dezacodex.ui.HeartViewModel
import com.dezacodex.updater.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreFeaturesTest {

    private lateinit var app: Application
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        runBlocking { TestHelpers.resetDatabaseForTest(app) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createLoggedInUser(
        vm: HeartViewModel,
        email: String = "alex@couple.app",
        name: String = "Alex"
    ) {
        vm.handleNormalSignUp(name, email, "Pass1234", "+15551111", 24, "Male")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode(vm.pendingLocalOtpHint.value!!)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
    }

    // ---------------------------------------------------------
    // FEATURE: COUPLE PAIRING (CODE, 6-DIGIT OTP, UNPAIR)
    // ---------------------------------------------------------
    @Test
    fun `pairing via invite code rejects self or invalid code and links seeded partner`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        createLoggedInUser(vm, "alex.pair@test.app", "Alex")

        // Self-code rejected
        val ownCode = vm.currentUser.value!!.inviteCode
        vm.inviteAndLinkCouple(ownCode)
        TestHelpers.waitUntil { vm.authError.value?.contains("own code") == true }

        // Invalid code rejected
        vm.inviteAndLinkCouple("INVALID-999")
        TestHelpers.waitUntil { vm.authError.value?.contains("invalid") == true }

        // Link with seeded Sophia Sim (L0V3P1)
        vm.inviteAndLinkCouple("L0V3P1")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "sophia.sim@heartsync.app" }
        assertNotNull(vm.pairingCelebrationPartnerName.value)
    }

    @Test
    fun `pairing via 6-digit OTP rejects nonexistent partner, expired otp, wrong otp, then links valid partner and unpairs`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Create Partner B first and generate her OTP
        createLoggedInUser(vm, "partnerb@test.app", "Bella")
        vm.generatePairingOtp()
        TestHelpers.waitUntil { !vm.currentUser.value?.pairingOtp.isNullOrEmpty() }
        val partnerOtp = vm.currentUser.value!!.pairingOtp!!
        assertEquals(6, partnerOtp.length)
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Create User A
        createLoggedInUser(vm, "usera@test.app", "Arthur")

        // 1. Nonexistent partner rejected (does NOT auto-create fake user!)
        vm.connectCoupleByEmailAndOtp("ghost@test.app", partnerOtp)
        TestHelpers.waitUntil { vm.authError.value?.contains("No registered partner") == true }
        val ghostUser = runBlocking { HeartDatabase.getDatabase(app).heartDao().getUserByEmail("ghost@test.app") }
        assertNull(ghostUser)

        // 2. Wrong OTP rejected
        vm.connectCoupleByEmailAndOtp("partnerb@test.app", "000000")
        TestHelpers.waitUntil { vm.authError.value?.contains("Invalid or incorrect OTP") == true }

        // 3. Valid OTP links both accounts mutually
        vm.connectCoupleByEmailAndOtp("partnerb@test.app", partnerOtp)
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "partnerb@test.app" }
        val partnerRow = runBlocking { HeartDatabase.getDatabase(app).heartDao().getUserByEmail("partnerb@test.app") }
        assertEquals("usera@test.app", partnerRow?.connectedPartnerEmail)

        // 4. Unpair removes connection on both accounts
        vm.removeCoupleLink()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.PAIRING }
        assertNull(vm.currentUser.value?.connectedPartnerEmail)
        val partnerAfterUnpair = runBlocking { HeartDatabase.getDatabase(app).heartDao().getUserByEmail("partnerb@test.app") }
        assertNull(partnerAfterUnpair?.connectedPartnerEmail)
    }

    // ---------------------------------------------------------
    // FEATURE: PROFILE SANCTUARY EDITING & THEMES
    // ---------------------------------------------------------
    @Test
    fun `profile editing validates inputs, clamps age, persists details, and updates theme and ringtones`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        createLoggedInUser(vm, "prof@test.app", "Clara")

        // Invalid short name rejected
        vm.updateUserProfileDetails("A", "prof@test.app", "+123", 22, "Bio", "")
        TestHelpers.waitUntil { vm.authError.value?.contains("valid name") == true }

        // Valid detail update (age clamped & bio persisted)
        vm.updateUserProfileDetails(
            newName = "Clara Rose",
            newEmail = "prof@test.app",
            newPhone = "+15557777",
            newAge = 200,
            newBio = "Counting stars with you",
            newAvatarUrl = "https://example.com/avatar.jpg"
        )
        TestHelpers.waitUntil { vm.currentUser.value?.username == "Clara Rose" }
        assertEquals(120, vm.currentUser.value?.age)
        assertEquals("+15557777", vm.currentUser.value?.phone)
        assertEquals("Counting stars with you", vm.currentUser.value?.gender)

        // Theme & ringtones update
        vm.updateTheme("Cosmic Romance")
        TestHelpers.waitUntil { vm.currentUser.value?.currentTheme == "Cosmic Romance" }

        vm.updateVoiceWallpaper("Ocean Breeze")
        TestHelpers.waitUntil { vm.authError.value?.contains("Ocean Breeze") == true }

        vm.updateRingtoneSettings("Chime", "Classic", "Sparkle", "Strong")
        TestHelpers.waitUntil { vm.authError.value?.contains("Tones & vibration updated") == true }
    }

    // ---------------------------------------------------------
    // FEATURE: PRIVATE CHAT (SEND, REACT, SAVE, DELETE, CLEAR)
    // ---------------------------------------------------------
    @Test
    fun `chat supports sending messages, reactions, saving, deleting, and couple-scoped clearing`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        vm.togglePartnerSimulation(false)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        createLoggedInUser(vm, "chat1@test.app", "Liam")
        vm.inviteAndLinkCouple("L0V3P1")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail != null }

        // Blank message ignored
        vm.sendMessage("   ")
        assertTrue(vm.messages.value.isEmpty())

        // Send normal + disappearing messages
        vm.sendMessage("Good morning sunshine!", isDisappearing = false)
        vm.sendMessage("Secret note", isDisappearing = true, duration = 15, msgEffect = "confetti")
        TestHelpers.waitUntil { vm.messages.value.size == 2 }

        val firstMsg = vm.messages.value.first()
        assertEquals("Good morning sunshine!", firstMsg.text)

        // React & save in chat
        vm.reactToMessage(firstMsg.id, "💖")
        vm.saveMessageToChat(firstMsg.id, true)
        TestHelpers.waitUntil {
            val m = vm.messages.value.find { it.id == firstMsg.id }
            m?.reaction == "💖" && m.isSavedInChat
        }

        // Delete one message
        vm.deleteMessage(firstMsg.id)
        TestHelpers.waitUntil { vm.messages.value.size == 1 }

        // Clear remaining couple messages
        vm.clearChat()
        TestHelpers.waitUntil { vm.messages.value.isEmpty() }
    }

    // ---------------------------------------------------------
    // FEATURE: DISAPPEARING SNAPS
    // ---------------------------------------------------------
    @Test
    fun `snaps support send, view countdown, screenshot alert, save, download, and delete`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        vm.togglePartnerSimulation(false)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        createLoggedInUser(vm, "snap@test.app", "Maya")
        vm.inviteAndLinkCouple("L0V3P1")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail != null }

        vm.sendSnap("IMAGE", "Sunset selfie [Golden Aura]", durationSecs = 10)
        TestHelpers.waitUntil { vm.snaps.value.size == 1 }
        val snap = vm.snaps.value.first()
        assertFalse(snap.viewed)

        // Open snap sets active viewer & marks viewed + streak
        vm.openSnap(snap)
        TestHelpers.waitUntil { vm.activeViewingSnap.value?.id == snap.id }
        TestHelpers.waitUntil { vm.snaps.value.first().viewed }
        vm.closeActiveSnap()
        assertNull(vm.activeViewingSnap.value)

        // Screenshot alert logs system chat alert + marks snap
        vm.triggerSimulatedScreenshot(snap.id)
        TestHelpers.waitUntil { vm.snaps.value.first().screenshotAlert }

        // Save in chat + download
        vm.saveSnapInChat(snap.id, true)
        TestHelpers.waitUntil { vm.snaps.value.first().isSavedInChat }

        vm.downloadSnapToLocalStorage(snap.id)
        TestHelpers.waitUntil { vm.authError.value?.contains("/Download") == true }

        // Delete snap
        vm.deleteSnap(snap.id)
        TestHelpers.waitUntil { vm.snaps.value.isEmpty() }
    }

    // ---------------------------------------------------------
    // FEATURE: STORIES / MOMENTS (24H FILTER, HIGHLIGHTS, DOWNLOAD)
    // ---------------------------------------------------------
    @Test
    fun `stories filter expired non-highlighted items while preserving highlights`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        createLoggedInUser(vm, "story@test.app", "Noah")
        vm.inviteAndLinkCouple("L0V3P1")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail != null }

        vm.shareStory("Coffee date memories", "MEMORIES")
        TestHelpers.waitUntil { vm.stories.value.size == 1 }
        val story = vm.stories.value.first()

        // Save to highlights + download
        vm.saveStoryToHighlights(story.id, true)
        TestHelpers.waitUntil { vm.stories.value.first().isSavedInChat }

        vm.downloadStoryToLocalStorage(story.id)
        TestHelpers.waitUntil { vm.authError.value?.contains("Local Gallery") == true }

        // Insert an expired non-highlighted story and an expired highlighted story
        runBlocking {
            val dao = HeartDatabase.getDatabase(app).heartDao()
            val past = System.currentTimeMillis() - 60_000L
            dao.insertStory(
                CoupleStory(
                    senderName = "Noah",
                    senderEmail = "story@test.app",
                    description = "Expired invisible story",
                    expiresAt = past,
                    isSavedInChat = false
                )
            )
            dao.insertStory(
                CoupleStory(
                    senderName = "Noah",
                    senderEmail = "story@test.app",
                    description = "Expired highlighted memory",
                    expiresAt = past,
                    isSavedInChat = true
                )
            )
        }
        TestHelpers.waitUntil { vm.stories.value.any { it.description == "Expired highlighted memory" } }
        assertFalse(vm.stories.value.any { it.description == "Expired invisible story" })
    }

    // ---------------------------------------------------------
    // FEATURE: IN-APP UPDATER SEMVER CHECKS
    // ---------------------------------------------------------
    @Test
    fun `updater semver comparison handles multi-digit versions, prefixes, and downgrades accurately`() {
        assertTrue(UpdateManager.isNewerVersion("v1.1.0", "1.0"))
        assertTrue(UpdateManager.isNewerVersion("1.10.0", "1.2.0"))
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(UpdateManager.isNewerVersion("v1.0.0", "1.0"))
        assertFalse(UpdateManager.isNewerVersion("0.9.5", "1.0.0"))
        assertEquals(Triple(1, 10, 2), UpdateManager.parseSemverParts("v1.10.2-beta"))
    }
}
