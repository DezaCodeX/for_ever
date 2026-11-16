package com.dezacodex.audit

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.TestHelpers
import com.dezacodex.call.AudioOutputDevice
import com.dezacodex.call.CallLayoutMode
import com.dezacodex.call.VadSensitivity
import com.dezacodex.call.VideoQualityPreset
import com.dezacodex.data.HeartDatabase
import com.dezacodex.data.HeartRepository
import com.dezacodex.data.User
import com.dezacodex.notifications.HeartNotificationHelper
import com.dezacodex.security.PasswordHasher
import com.dezacodex.ui.AppScreen
import com.dezacodex.ui.HeartViewModel
import com.dezacodex.updater.UpdateManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComprehensiveFiveCasePerFunctionTest {

    private lateinit var app: Application
    private lateinit var db: HeartDatabase
    private lateinit var repo: HeartRepository
    private lateinit var vm: HeartViewModel

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        TestHelpers.resetDatabaseForTest(app)
        db = HeartDatabase.getDatabase(app)
        repo = HeartRepository(db.heartDao())
        repo.ensureProfileExists()
        vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.idleMainLooper()
    }

    @After
    fun tearDown() = runBlocking {
        vm.hangUpCall()
        TestHelpers.resetDatabaseForTest(app)
    }

    private suspend fun seedVerifiedLoggedInUser(
        email: String = "alice@hs.test",
        name: String = "Alice",
        password: String = "StrongPass123"
    ): User {
        val created = repo.registerUser(email, name, password, "+15550101").getOrThrow()
            .copy(isOtpVerified = true)
        repo.updateUser(created)
        vm.handleNormalLogin(email, password)
        TestHelpers.waitUntil { vm.currentUser.value?.email == email }
        return vm.currentUser.value!!
    }

    // =========================================================================
    // 1. ONBOARDING, NAVIGATION & SANDBOX PARTNER SEEDING (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `nav_01 toggleOfflineSandboxMode transitions SPLASH to WELCOME and seeds Sophia Sim`() = runBlocking {
        TestHelpers.waitUntil {
            db.heartDao().getUserByEmail("sophia.sim@heartsync.app") != null
        }
        val partner = db.heartDao().getUserByEmail("sophia.sim@heartsync.app")!!
        assertThat(partner.inviteCode).isEqualTo("L0V3P1")
        assertThat(vm.forceOfflineMode.value).isTrue()
    }

    @Test
    fun `nav_02 skipOnboarding routes from ONBOARDING to WELCOME`() {
        vm.navigateTo(AppScreen.ONBOARDING)
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.ONBOARDING)
        vm.skipOnboarding()
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.WELCOME)
    }

    @Test
    fun `nav_03 navigateTo switches between WELCOME LOGIN SIGNUP and FORGOT_PWD`() {
        vm.navigateTo(AppScreen.LOGIN)
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.LOGIN)
        vm.navigateTo(AppScreen.SIGNUP)
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.SIGNUP)
        vm.navigateTo(AppScreen.FORGOT_PWD)
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.FORGOT_PWD)
    }

    @Test
    fun `nav_04 sandbox user can pair directly with seeded L0V3P1 demo partner`() = runBlocking {
        seedVerifiedLoggedInUser("romeo@hs.test", "Romeo")
        vm.inviteAndLinkCouple("l0v3p1")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "sophia.sim@heartsync.app" }
        assertThat(vm.pairingCelebrationPartnerName.value).isEqualTo("Sophia Sim")
        vm.clearPairingCelebration()
        assertThat(vm.pairingCelebrationPartnerName.value).isNull()
    }

    @Test
    fun `nav_05 logout clears session email active user and returns to WELCOME`() = runBlocking {
        seedVerifiedLoggedInUser("juliet@hs.test", "Juliet")
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME && vm.currentUser.value == null }
        assertThat(db.heartDao().getProfileDirect()?.loggedInUserEmail).isEmpty()
    }

    // =========================================================================
    // 2. OTP VERIFICATION & RESEND FLOW (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `otp_01 handleNormalSignUp creates unverified user and issues 6 digit OTP`() = runBlocking {
        vm.handleNormalSignUp("Maya", "maya@hs.test", "Secret123", "5551112222", 24, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        val otp = vm.pendingLocalOtpHint.value
        assertThat(otp).isNotNull()
        assertThat(otp!!.length).isEqualTo(6)
        assertThat(db.heartDao().getUserByEmail("maya@hs.test")!!.isOtpVerified).isFalse()
    }

    @Test
    fun `otp_02 submitOtpCode rejects wrong 6 digit code and preserves OTP_VERIFY screen`() = runBlocking {
        vm.handleNormalSignUp("Maya", "maya@hs.test", "Secret123", "5551112222", 24, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode("000000")
        TestHelpers.waitUntil { vm.authError.value?.contains("Incorrect or expired") == true }
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.OTP_VERIFY)
    }

    @Test
    fun `otp_03 submitOtpCode rejects blank input`() = runBlocking {
        vm.handleNormalSignUp("Maya", "maya@hs.test", "Secret123", "5551112222", 24, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode("   ")
        TestHelpers.waitUntil { vm.authError.value?.contains("Incorrect or expired") == true }
    }

    @Test
    fun `otp_04 resendVerificationOtp rotates OTP code and accepts newly issued code`() = runBlocking {
        vm.handleNormalSignUp("Maya", "maya@hs.test", "Secret123", "5551112222", 24, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.resendVerificationOtp()
        val refreshedOtp = vm.pendingLocalOtpHint.value!!
        vm.submitOtpCode(refreshedOtp)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        assertThat(db.heartDao().getUserByEmail("maya@hs.test")!!.isOtpVerified).isTrue()
    }

    @Test
    fun `otp_05 login with unverified account redirects to OTP_VERIFY instead of MAIN`() = runBlocking {
        val raw = repo.registerUser("unverified@hs.test", "Unverified", "Pass1234", "").getOrThrow()
        repo.updateUser(raw.copy(isOtpVerified = false))
        vm.handleNormalLogin("unverified@hs.test", "Pass1234")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        assertThat(vm.pendingLocalOtpHint.value).hasLength(6)
    }

    // =========================================================================
    // 3. THREE-PHASE PASSWORD RESET FLOW (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `pwd_01 startForgotPasswordReset rejects unregistered email`() = runBlocking {
        vm.startForgotPasswordReset("nobody@hs.test")
        TestHelpers.waitUntil { vm.authError.value?.contains("No registered local account") == true }
        assertThat(vm.forgotPasswordPhase.value).isEqualTo(1)
    }

    @Test
    fun `pwd_02 startForgotPasswordReset advances registered account to phase 2 with OTP`() = runBlocking {
        repo.registerUser("resetme@hs.test", "ResetUser", "OldPass123")
        vm.startForgotPasswordReset("resetme@hs.test")
        TestHelpers.waitUntil { vm.forgotPasswordPhase.value == 2 }
        assertThat(vm.pendingLocalOtpHint.value).hasLength(6)
    }

    @Test
    fun `pwd_03 verifyForgotOtp rejects invalid code and stays in phase 2`() = runBlocking {
        repo.registerUser("resetme@hs.test", "ResetUser", "OldPass123")
        vm.startForgotPasswordReset("resetme@hs.test")
        TestHelpers.waitUntil { vm.forgotPasswordPhase.value == 2 }
        vm.verifyForgotOtp("999999")
        assertThat(vm.forgotPasswordPhase.value).isEqualTo(2)
        assertThat(vm.authError.value).contains("Incorrect or expired")
    }

    @Test
    fun `pwd_04 submitNewPassword rejects short password under 6 chars`() = runBlocking {
        repo.registerUser("resetme@hs.test", "ResetUser", "OldPass123")
        vm.startForgotPasswordReset("resetme@hs.test")
        TestHelpers.waitUntil { vm.forgotPasswordPhase.value == 2 }
        vm.verifyForgotOtp(vm.pendingLocalOtpHint.value!!)
        assertThat(vm.forgotPasswordPhase.value).isEqualTo(3)

        vm.submitNewPassword("123")
        TestHelpers.waitUntil { vm.authError.value?.contains("at least 6 characters") == true }
    }

    @Test
    fun `pwd_05 complete 3 phase password reset updates PBKDF2 hash and logs user in`() = runBlocking {
        repo.registerUser("resetme@hs.test", "ResetUser", "OldPass123")
        vm.startForgotPasswordReset("resetme@hs.test")
        TestHelpers.waitUntil { vm.forgotPasswordPhase.value == 2 }
        vm.verifyForgotOtp(vm.pendingLocalOtpHint.value!!)
        vm.submitNewPassword("BrandNewPass99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }

        assertThat(repo.loginUser("resetme@hs.test", "OldPass123")).isNull()
        assertThat(repo.loginUser("resetme@hs.test", "BrandNewPass99")).isNotNull()
    }

    // =========================================================================
    // 4. GOOGLE SIGN-IN GUARDRAILS & HIJACK PROTECTION (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `google_01 triggerGoogleSignIn rejects malformed email`() = runBlocking {
        vm.triggerGoogleSignIn("not-an-email", "Bad Email")
        TestHelpers.waitUntil { vm.authError.value?.contains("valid Google email") == true }
        assertThat(vm.currentUser.value).isNull()
    }

    @Test
    fun `google_02 triggerGoogleSignIn blocks unverified admin spoofing for dezacodex`() = runBlocking {
        vm.triggerGoogleSignIn("dezacodex@gmail.com", "Spoofer")
        TestHelpers.waitUntil { vm.authError.value?.contains("Admin accounts require verified") == true }
        assertThat(vm.currentUser.value).isNull()
    }

    @Test
    fun `google_03 triggerGoogleSignIn blocks unverified admin spoofing for rdccpscollege`() = runBlocking {
        vm.triggerGoogleSignIn("rdccpscollege@gmail.com", "Spoofer2")
        TestHelpers.waitUntil { vm.authError.value?.contains("Admin accounts require verified") == true }
        assertThat(vm.currentUser.value).isNull()
    }

    @Test
    fun `google_04 triggerGoogleSignIn blocks hijacking existing password protected account`() = runBlocking {
        repo.registerUser("victim@hs.test", "Victim", "VictimSecret99")
        vm.triggerGoogleSignIn("victim@hs.test", "Attacker")
        TestHelpers.waitUntil { vm.authError.value?.contains("already registered with a password") == true }
        assertThat(vm.currentUser.value).isNull()
    }

    @Test
    fun `google_05 signInWithGoogleCredentialManager in sandbox mode creates verified Google user`() = runBlocking {
        vm.signInWithGoogleCredentialManager(app)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        assertThat(vm.currentUser.value?.email).isEqualTo("sandbox.google@heartsync.app")
        assertThat(vm.currentUser.value?.isGoogleUser).isTrue()
    }

    // =========================================================================
    // 5. SIX-DIGIT PAIRING OTP EXCHANGE (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `pairotp_01 generatePairingOtp issues 6 digit code with future expiry`() = runBlocking {
        seedVerifiedLoggedInUser("p1@hs.test", "PartnerOne")
        vm.generatePairingOtp()
        TestHelpers.waitUntil { !vm.currentUser.value?.pairingOtp.isNullOrBlank() }
        assertThat(vm.currentUser.value!!.pairingOtp).hasLength(6)
        assertThat(vm.currentUser.value!!.pairingOtpExpiry).isGreaterThan(System.currentTimeMillis())
    }

    @Test
    fun `pairotp_02 connectCoupleByEmailAndOtp links two accounts with valid OTP`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234").copy(
            isOtpVerified = true,
            pairingOtp = "654321",
            pairingOtpExpiry = System.currentTimeMillis() + 120_000L
        )
        repo.updateUser(bob)
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")

        vm.connectCoupleByEmailAndOtp("bob@hs.test", "654321")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }
        assertThat(db.heartDao().getUserByEmail("bob@hs.test")!!.connectedPartnerEmail).isEqualTo("alice@hs.test")
    }

    @Test
    fun `pairotp_03 connectCoupleByEmailAndOtp rejects wrong OTP`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234").copy(
            isOtpVerified = true,
            pairingOtp = "654321",
            pairingOtpExpiry = System.currentTimeMillis() + 120_000L
        )
        repo.updateUser(bob)
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")

        vm.connectCoupleByEmailAndOtp("bob@hs.test", "111111")
        TestHelpers.waitUntil { vm.authError.value?.contains("Invalid or incorrect OTP") == true }
    }

    @Test
    fun `pairotp_04 connectCoupleByEmailAndOtp rejects expired OTP`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234").copy(
            isOtpVerified = true,
            pairingOtp = "654321",
            pairingOtpExpiry = System.currentTimeMillis() - 10_000L
        )
        repo.updateUser(bob)
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")

        vm.connectCoupleByEmailAndOtp("bob@hs.test", "654321")
        TestHelpers.waitUntil { vm.authError.value?.contains("expired") == true }
    }

    @Test
    fun `pairotp_05 connectCoupleByEmailAndOtp blocks self pairing`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.connectCoupleByEmailAndOtp("alice@hs.test", "123456")
        TestHelpers.waitUntil { vm.authError.value?.contains("cannot pair with your own email") == true }
    }

    // =========================================================================
    // 6. MEDIA ATTACHMENTS & SCOPED CHAT CLEAR (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `media_01 sendMessage with mediaUri and blank text stores fallback caption and mediaUrl`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        repo.linkCouple("alice@hs.test", bob.inviteCode)
        vm.handleNormalLogin("alice@hs.test", "StrongPass123")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        val uri = Uri.parse("content://media/external/images/media/42")
        vm.sendMessage(text = "", mediaUri = uri)
        TestHelpers.waitUntil { vm.messages.value.any { it.mediaUrl == uri.toString() } }
        val stored = vm.messages.value.first { it.mediaUrl == uri.toString() }
        assertThat(stored.text).isEqualTo("Shared a photo")
    }

    @Test
    fun `media_02 sendMessage ignores empty text when mediaUri is null`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.sendMessage(text = "   ", mediaUri = null)
        TestHelpers.idleMainLooper()
        assertThat(db.heartDao().getAllMessagesDirect()).isEmpty()
    }

    @Test
    fun `media_03 clearChatForCouple deletes only active couple messages and preserves third party chat`(): Unit = runBlocking {
        repo.sendMessage("Alice", "Couple msg", senderEmail = "alice@hs.test", receiverEmail = "bob@hs.test")
        repo.sendMessage("Eve", "Other couple msg", senderEmail = "eve@hs.test", receiverEmail = "dan@hs.test")

        repo.clearChatForCouple("alice@hs.test", "bob@hs.test")
        val remaining = db.heartDao().getAllMessagesDirect()
        assertThat(remaining.map { it.text }).containsExactly("Other couple msg")
    }

    @Test
    fun `media_04 sendSnap with mediaUri persists mediaUrl in CoupleSnap`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        repo.linkCouple("alice@hs.test", bob.inviteCode)
        vm.handleNormalLogin("alice@hs.test", "StrongPass123")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        val uri = Uri.parse("content://media/external/images/media/99")
        vm.sendSnap("IMAGE", "", 10, mediaUri = uri)
        TestHelpers.waitUntil { vm.snaps.value.any { it.mediaUrl == uri.toString() } }
        assertThat(vm.snaps.value.first().description).isEqualTo("Snap Photo")
    }

    @Test
    fun `media_05 shareStory with mediaUri persists mediaUrl in CoupleStory`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        val uri = Uri.parse("content://media/external/images/media/108")
        vm.shareStory("", "DATE_NIGHT", mediaUri = uri)
        TestHelpers.waitUntil { vm.stories.value.any { it.mediaUrl == uri.toString() } }
        assertThat(vm.stories.value.first().description).isEqualTo("Shared a moment")
    }

    // =========================================================================
    // 7. NOTIFICATIONS: CHANNELS, READ STATE & ALERTS (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `notif_01 channelForType maps PAIRING to STREAKS and STORY to SNAPS`() {
        assertThat(HeartNotificationHelper.channelForType("PAIRING"))
            .isEqualTo(HeartNotificationHelper.CHANNEL_STREAKS)
        assertThat(HeartNotificationHelper.channelForType("STORY"))
            .isEqualTo(HeartNotificationHelper.CHANNEL_SNAPS)
    }

    @Test
    fun `notif_02 pairing generates unread PAIRING notification in ViewModel feed`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.notifications.value.any { it.type == "PAIRING" } }
        assertThat(vm.notifications.value.first { it.type == "PAIRING" }.isRead).isFalse()
    }

    @Test
    fun `notif_03 markNotificationRead marks target item read without affecting others`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.notifications.value.isNotEmpty() }
        vm.receiveSimulatedCall("AUDIO")
        TestHelpers.waitUntil { vm.notifications.value.size >= 2 }

        val firstId = vm.notifications.value.first().id
        vm.markNotificationRead(firstId)
        assertThat(vm.notifications.value.first { it.id == firstId }.isRead).isTrue()
        assertThat(vm.notifications.value.count { !it.isRead }).isAtLeast(1)
    }

    @Test
    fun `notif_04 markAllNotificationsRead marks every notification item read`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.notifications.value.isNotEmpty() }
        vm.receiveSimulatedCall("VIDEO")
        TestHelpers.waitUntil { vm.notifications.value.size >= 2 }

        vm.markAllNotificationsRead()
        assertThat(vm.notifications.value.all { it.isRead }).isTrue()
    }

    @Test
    fun `notif_05 triggerSimulatedScreenshot appends SCREENSHOT notification and SYSTEM chat alert`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        repo.sendSnap("Bob", "IMAGE", "Top secret", 10, "bob@hs.test", "alice@hs.test")
        val snap = repo.getSnapsForCouple("alice@hs.test", "bob@hs.test").first().first()
        vm.triggerSimulatedScreenshot(snap.id)

        TestHelpers.waitUntil { vm.notifications.value.any { it.type == "SCREENSHOT" } }
        val sysMsgs = db.heartDao().getAllMessagesDirect().filter { it.senderName == "SYSTEM" }
        assertThat(sysMsgs).isNotEmpty()
    }

    // =========================================================================
    // 8. PROFILE VALIDATION, AVATAR, RINGTONES & WALLPAPER (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `prof_01 updateUserProfileDetails rejects short display name under 2 chars`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.updateUserProfileDetails("A", "alice@hs.test", "5550000", 25, "Bio", "")
        assertThat(vm.authError.value).contains("at least 2 characters")
    }

    @Test
    fun `prof_02 updateUserProfileDetails rejects invalid email format`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.updateUserProfileDetails("Alice Updated", "invalid-email", "5550000", 25, "Bio", "")
        assertThat(vm.authError.value).contains("valid email")
    }

    @Test
    fun `prof_03 updateUserProfileDetails clamps extreme age into 13 to 120 bounds`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.updateUserProfileDetails("Alice Updated", "alice@hs.test", "5550000", 999, "Bio", "")
        TestHelpers.waitUntil { vm.currentUser.value?.age == 120 }
        assertThat(vm.currentUser.value?.age).isEqualTo(120)
    }

    @Test
    fun `prof_04 uploadAndSaveAvatar in offline mode updates local avatarUrl cleanly`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        val avatarUri = Uri.parse("content://media/external/images/media/777")
        vm.uploadAndSaveAvatar(avatarUri)
        TestHelpers.waitUntil { vm.currentUser.value?.avatarUrl == avatarUri.toString() }
        assertThat(db.heartDao().getUserByEmail("alice@hs.test")?.avatarUrl).isEqualTo(avatarUri.toString())
    }

    @Test
    fun `prof_05 updateRingtoneSettings and updateVoiceWallpaper persist preferences`() = runBlocking {
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.updateRingtoneSettings("Crystal Harp", "Romantic Piano", "Starlight Synth", "Strong")
        vm.updateVoiceWallpaper("Aurora Borealis")
        TestHelpers.waitUntil {
            db.heartDao().getProfileDirect()?.voiceCallWallpaper == "Aurora Borealis"
        }
        val profile = db.heartDao().getProfileDirect()!!
        assertThat(profile.notificationTone).isEqualTo("Crystal Harp")
        assertThat(profile.videoCallRingtone).isEqualTo("Starlight Synth")
    }

    // =========================================================================
    // 9. ADMIN RBAC & SEMVER RELEASE UPDATER (6 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `rbac_01 rdccpscollege admin login routes directly to ADMIN dashboard`() = runBlocking {
        val admin = repo.registerUser("rdccpscollege@gmail.com", "Owner Admin", "AdminPass99")
            .copy(isOtpVerified = true)
        repo.updateUser(admin)
        vm.handleNormalLogin("rdccpscollege@gmail.com", "AdminPass99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.ADMIN }
        assertThat(vm.currentScreen.value).isEqualTo(AppScreen.ADMIN)
    }

    @Test
    fun `rbac_02 non admin caller is blocked from adminDeleteUser`() = runBlocking {
        repo.registerUser("target@hs.test", "Target", "Pass1234")
        seedVerifiedLoggedInUser("regular@hs.test", "Regular")
        vm.adminDeleteUser("target@hs.test")
        assertThat(vm.authError.value).contains("Unauthorized")
        assertThat(db.heartDao().getUserByEmail("target@hs.test")).isNotNull()
    }

    @Test
    fun `rbac_03 admin cannot delete protected rdccpscollege or dezacodex admin accounts`() = runBlocking {
        val admin = repo.registerUser("rdccpscollege@gmail.com", "Owner Admin", "AdminPass99")
            .copy(isOtpVerified = true)
        repo.updateUser(admin)
        vm.handleNormalLogin("rdccpscollege@gmail.com", "AdminPass99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.ADMIN }

        vm.adminDeleteUser("rdccpscollege@gmail.com")
        assertThat(vm.authError.value).contains("Cannot delete primary admin")
    }

    @Test
    fun `rbac_04 verified admin deletes regular user account cleanly`() = runBlocking {
        repo.registerUser("removable@hs.test", "Removable", "Pass1234")
        val admin = repo.registerUser("dezacodex@gmail.com", "Primary Admin", "AdminPass99")
            .copy(isOtpVerified = true)
        repo.updateUser(admin)
        vm.handleNormalLogin("dezacodex@gmail.com", "AdminPass99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.ADMIN }

        vm.adminDeleteUser("removable@hs.test")
        TestHelpers.waitUntil { db.heartDao().getUserByEmail("removable@hs.test") == null }
    }

    @Test
    fun `semver_05 UpdateManager compares multi segment SemVer accurately without float truncation`() {
        assertThat(UpdateManager.isNewerVersion(candidateVersion = "v1.0.10", currentVersion = "1.0.2")).isTrue()
        assertThat(UpdateManager.isNewerVersion(candidateVersion = "v1.0.2", currentVersion = "1.0.10")).isFalse()
        assertThat(UpdateManager.isNewerVersion(candidateVersion = "1.0.0", currentVersion = "1.0")).isFalse()
    }

    @Test
    fun `semver_06 UpdateManager handles malformed tags safely without throwing`() {
        assertThat(UpdateManager.isNewerVersion(candidateVersion = "release-alpha", currentVersion = "1.0")).isFalse()
        assertThat(UpdateManager.parseSemverParts("")).isEqualTo(Triple(0, 0, 0))
    }

    // =========================================================================
    // 10. DISCORD MULTI-STREAM CALL VIEWMODEL INTEGRATION (5 DISTINCT TESTS)
    // =========================================================================

    @Test
    fun `callvm_01 initiateCall blocks unpaired user with clear error`() = runBlocking {
        seedVerifiedLoggedInUser("solo@hs.test", "Solo")
        vm.initiateCall("VIDEO")
        assertThat(vm.callState.value).isEqualTo("IDLE")
        assertThat(vm.authError.value).contains("Only connected couples can call")
    }

    @Test
    fun `callvm_02 initiateCall for paired couple transitions to OUTGOING and logs CALL notification`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        vm.initiateCall("VIDEO")
        TestHelpers.waitUntil { vm.callState.value != "IDLE" }
        assertThat(vm.notifications.value.any { it.type == "CALL" }).isTrue()
    }

    @Test
    fun `callvm_03 receiveSimulatedCall and acceptIncomingCall transition INCOMING to CONNECTED`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        vm.receiveSimulatedCall("VIDEO")
        assertThat(vm.callUiState.value.callStatus).isEqualTo("INCOMING")
        vm.acceptIncomingCall()
        assertThat(vm.callUiState.value.callStatus).isEqualTo("CONNECTED")
    }

    @Test
    fun `callvm_04 startScreenShare preserves webcam stream and switches layout to FOCUS`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        vm.callController.updatePermissions(hasCamera = true, hasMic = true)
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        vm.receiveSimulatedCall("VIDEO")
        vm.acceptIncomingCall()
        assertThat(vm.callUiState.value.localCamEnabled).isTrue()

        vm.startScreenShare(Intent("TEST_PROJECTION"), captureAudio = true)
        assertThat(vm.callUiState.value.isScreenSharing).isTrue()
        assertThat(vm.callUiState.value.localCamEnabled).isTrue()
        assertThat(vm.callUiState.value.layoutMode).isEqualTo(CallLayoutMode.FOCUS)

        vm.setCallLayoutMode(CallLayoutMode.GRID)
        assertThat(vm.callUiState.value.layoutMode).isEqualTo(CallLayoutMode.GRID)
        vm.selectAudioOutput(AudioOutputDevice.BLUETOOTH)
        vm.setVadSensitivity(VadSensitivity.HIGH)
        vm.setVideoQualityPreset(VideoQualityPreset.HIGH)
        assertThat(vm.callUiState.value.selectedAudioOutput).isEqualTo(AudioOutputDevice.BLUETOOTH)
        assertThat(vm.callUiState.value.vadSensitivity).isEqualTo(VadSensitivity.HIGH)
        assertThat(vm.callUiState.value.qualityPreset).isEqualTo(VideoQualityPreset.HIGH)
    }

    @Test
    fun `callvm_05 hangUpCall cleans up screen share mic cam and returns callState to IDLE`() = runBlocking {
        val bob = repo.registerUser("bob@hs.test", "Bob", "Pass1234")
        seedVerifiedLoggedInUser("alice@hs.test", "Alice")
        vm.inviteAndLinkCouple(bob.inviteCode)
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail == "bob@hs.test" }

        vm.receiveSimulatedCall("VIDEO")
        vm.acceptIncomingCall()
        vm.startScreenShare(Intent("TEST_PROJECTION"))
        vm.hangUpCall()

        assertThat(vm.callState.value).isEqualTo("IDLE")
        assertThat(vm.callUiState.value.isScreenSharing).isFalse()
        assertThat(vm.callUiState.value.participants).isEmpty()
    }
}
