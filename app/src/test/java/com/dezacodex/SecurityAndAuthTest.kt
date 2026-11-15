package com.dezacodex

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.data.HeartDatabase
import com.dezacodex.data.HeartRepository
import com.dezacodex.data.User
import com.dezacodex.security.InputValidator
import com.dezacodex.security.PasswordHasher
import com.dezacodex.ui.AppScreen
import com.dezacodex.ui.HeartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class SecurityAndAuthTest {

    private lateinit var app: Application
    private lateinit var inMemoryDb: HeartDatabase
    private lateinit var repository: HeartRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        inMemoryDb = Room.inMemoryDatabaseBuilder(app, HeartDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HeartRepository(inMemoryDb.heartDao())
        runBlocking { TestHelpers.resetDatabaseForTest(app) }
    }

    @After
    fun tearDown() {
        inMemoryDb.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------
    // 1. PASSWORD HASHING & VALIDATION UNIT TESTS
    // ---------------------------------------------------------
    @Test
    fun `password hasher produces unique salted pbkdf2 hashes and verifies correctly`() {
        val h1 = PasswordHasher.hashPassword("SecretKey99")
        val h2 = PasswordHasher.hashPassword("SecretKey99")

        assertTrue(h1.startsWith("pbkdf2$"))
        assertNotEquals("Salted hashes for same input must differ", h1, h2)
        assertTrue(PasswordHasher.verifyPassword("SecretKey99", h1))
        assertFalse(PasswordHasher.verifyPassword("WrongPass!", h1))
        assertFalse(PasswordHasher.verifyPassword("", h1))
    }

    @Test
    fun `secure otp generator creates exact length numeric pin`() {
        val otp6 = PasswordHasher.generateSecureOtp(6)
        assertEquals(6, otp6.length)
        assertTrue(otp6.all { it.isDigit() })
    }

    @Test
    fun `input validator enforces email, password, age, and sanitization rules`() {
        assertEquals("user@example.com", InputValidator.normalizeEmail("  User@Example.COM "))
        assertTrue(InputValidator.isValidEmail("lover@heartsync.app"))
        assertFalse(InputValidator.isValidEmail("not-an-email"))
        assertNotNull(InputValidator.validateLogin("bad", "123456"))
        assertNotNull(InputValidator.validateLogin("a@b.com", "123"))
        assertNull(InputValidator.validateLogin("a@b.com", "123456"))
        assertNotNull(InputValidator.validateRegistration("A", "a@b.com", "123456", 20))
        assertNotNull(InputValidator.validateRegistration("Alice", "a@b.com", "123456", 10))
        assertNull(InputValidator.validateRegistration("Alice", "a@b.com", "123456", 22))

        val sanitized = InputValidator.sanitizeDisplayText("  hello\u0000world  ", maxLength = 7)
        assertEquals("hellowo", sanitized)
    }

    // ---------------------------------------------------------
    // 2. REPOSITORY REGISTRATION & LOGIN TESTS
    // ---------------------------------------------------------
    @Test
    fun `repository rejects duplicate email registration and never stores plaintext password`() = runBlocking {
        val first = repository.registerUser(
            email = "alice@test.app",
            username = "Alice",
            rawPassword = "MyPassword123",
            phone = "+15551000"
        )
        assertTrue(first.isSuccess)
        val stored = first.getOrThrow()
        assertNotEquals("MyPassword123", stored.passwordHash)
        assertTrue(stored.passwordHash.startsWith("pbkdf2$"))

        val duplicate = repository.registerUser(
            email = "ALICE@test.app",
            username = "Attacker",
            rawPassword = "OtherPassword",
            phone = "+15559999"
        )
        assertTrue("Duplicate registration must fail", duplicate.isFailure)
    }

    @Test
    fun `repository upgrades legacy plaintext hash transparently on login`() = runBlocking {
        inMemoryDb.heartDao().insertUser(
            User(
                email = "legacy@test.app",
                username = "Legacy",
                passwordHash = "OldPlain123",
                isOtpVerified = true
            )
        )
        val wrong = repository.loginUser("legacy@test.app", "WrongPass")
        assertNull(wrong)

        val ok = repository.loginUser("legacy@test.app", "OldPlain123")
        assertNotNull(ok)
        val upgraded = inMemoryDb.heartDao().getUserByEmail("legacy@test.app")!!
        assertTrue("Legacy plaintext password must be upgraded to PBKDF2", upgraded.passwordHash.startsWith("pbkdf2$"))
    }

    // ---------------------------------------------------------
    // 3. VIEWMODEL LOGIN / SIGNUP / OTP / FORGOT PASSWORD / RBAC
    // ---------------------------------------------------------
    @Test
    fun `signup creates unverified user, rejects wrong otp, allows resend, and activates on valid otp`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        vm.handleNormalSignUp("Romeo", "romeo@test.app", "Pass1234", "+15550101", 23, "Male")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }

        val issuedOtp = vm.pendingLocalOtpHint.value
        assertNotNull("Offline sandbox should issue a 6-digit OTP", issuedOtp)
        assertEquals(6, issuedOtp!!.length)

        // Ensure hardcoded bypass PIN 2212 no longer works!
        if (issuedOtp != "2212") {
            vm.submitOtpCode("2212")
            TestHelpers.waitUntil { vm.authError.value?.contains("Incorrect") == true }
            assertEquals(AppScreen.OTP_VERIFY, vm.currentScreen.value)
        }

        // Resend issues a new OTP and verify succeeds
        vm.resendVerificationOtp()
        TestHelpers.waitUntil { vm.authError.value?.contains("new 6-digit") == true }
        val latestOtp = vm.pendingLocalOtpHint.value!!

        vm.submitOtpCode(latestOtp)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        assertEquals("romeo@test.app", vm.currentUser.value?.email)
        assertTrue(vm.currentUser.value?.isOtpVerified == true)
    }

    @Test
    fun `login rejects invalid credentials and unverified accounts then succeeds cleanly`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Empty/invalid email
        vm.handleNormalLogin("invalid", "123456")
        assertNotNull(vm.authError.value)

        // Register & verify
        vm.handleNormalSignUp("Juliet", "juliet@test.app", "Secret99", "", 21, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        val otp = vm.pendingLocalOtpHint.value!!

        // Attempting login before OTP verification redirects back to OTP screen (not MAIN!)
        vm.navigateTo(AppScreen.LOGIN)
        vm.handleNormalLogin("juliet@test.app", "Secret99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }

        // Verify newly generated OTP
        val freshOtp = vm.pendingLocalOtpHint.value!!
        vm.submitOtpCode(freshOtp)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }

        // Logout clears user session & returns to WELCOME
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        assertNull(vm.currentUser.value)

        // Wrong password fails
        vm.handleNormalLogin("juliet@test.app", "WrongPwd1")
        TestHelpers.waitUntil { vm.authError.value?.contains("Invalid") == true }
        assertEquals(AppScreen.WELCOME, vm.currentScreen.value)

        // Valid login succeeds
        vm.handleNormalLogin("juliet@test.app", "Secret99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        assertEquals("juliet@test.app", vm.currentUser.value?.email)
        assertNotEquals("", otp)
    }

    @Test
    fun `forgot password 3-phase flow resets password and rejects old password`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        vm.handleNormalSignUp("ResetUser", "reset@test.app", "OldPass1", "", 22, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode(vm.pendingLocalOtpHint.value!!)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Unknown email blocked
        vm.triggerPasswordReset("nobody@test.app")
        TestHelpers.waitUntil { vm.authError.value?.contains("No registered") == true }
        assertEquals(1, vm.forgotPasswordPhase.value)

        // Valid email -> Phase 2
        vm.triggerPasswordReset("reset@test.app")
        TestHelpers.waitUntil { vm.forgotPasswordPhase.value == 2 }
        val resetOtp = vm.pendingLocalOtpHint.value!!

        // Wrong OTP rejected
        vm.verifyForgotOtp("000000")
        assertEquals(2, vm.forgotPasswordPhase.value)

        // Valid OTP -> Phase 3
        vm.verifyForgotOtp(resetOtp)
        assertEquals(3, vm.forgotPasswordPhase.value)

        // Too-short new password rejected
        vm.submitNewPassword("123")
        assertEquals(3, vm.forgotPasswordPhase.value)

        // Valid new password succeeds
        vm.submitNewPassword("NewPass99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Old password fails, new password succeeds
        vm.handleNormalLogin("reset@test.app", "OldPass1")
        TestHelpers.waitUntil { vm.authError.value?.contains("Invalid") == true }

        vm.handleNormalLogin("reset@test.app", "NewPass99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
    }

    @Test
    fun `google signin blocks admin impersonation and password account hijacking`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Block admin email via Google bypass
        vm.triggerGoogleSignIn(HeartViewModel.ADMIN_EMAIL, "Fake Admin")
        TestHelpers.waitUntil { vm.authError.value?.contains("Admin") == true }
        assertEquals(AppScreen.WELCOME, vm.currentScreen.value)

        // Register password user first
        vm.handleNormalSignUp("RealUser", "real@test.app", "Pass1234", "", 25, "Male")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode(vm.pendingLocalOtpHint.value!!)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Block password account hijack via Google Sign-In
        vm.triggerGoogleSignIn("real@test.app", "Attacker")
        TestHelpers.waitUntil { vm.authError.value?.contains("password") == true }
        assertEquals(AppScreen.WELCOME, vm.currentScreen.value)

        // Valid fresh Google user succeeds
        vm.triggerGoogleSignIn("googleuser@gmail.com", "GoogleLover")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        assertEquals("googleuser@gmail.com", vm.currentUser.value?.email)
    }

    @Test
    fun `admin rbac blocks non-admin deletion and allows verified admin to delete regular users`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Create two users
        vm.handleNormalSignUp("UserOne", "one@test.app", "Pass1234", "", 22, "Female")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode(vm.pendingLocalOtpHint.value!!)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }

        // Non-admin cannot delete accounts
        vm.adminDeleteUser("sophia.sim@heartsync.app")
        TestHelpers.waitUntil { vm.authError.value?.contains("Unauthorized") == true }
        vm.logout()
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        // Seed verified admin directly in DB with hashed password and log in
        runBlocking {
            HeartDatabase.getDatabase(app).heartDao().insertUser(
                User(
                    email = HeartViewModel.ADMIN_EMAIL,
                    username = "Admin",
                    passwordHash = PasswordHasher.hashPassword("AdminSecret99"),
                    isOtpVerified = true
                )
            )
        }
        vm.handleNormalLogin(HeartViewModel.ADMIN_EMAIL, "AdminSecret99")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.ADMIN }

        // Admin cannot delete self
        vm.adminDeleteUser(HeartViewModel.ADMIN_EMAIL)
        TestHelpers.waitUntil { vm.authError.value?.contains("Cannot delete primary admin") == true }

        // Admin deletes one@test.app cleanly
        vm.adminDeleteUser("one@test.app")
        TestHelpers.waitUntil { vm.authError.value?.contains("Successfully removed") == true }
        val deletedCheck = runBlocking { HeartDatabase.getDatabase(app).heartDao().getUserByEmail("one@test.app") }
        assertNull(deletedCheck)
    }
}
