package com.dezacodex.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.data.HeartDatabase
import com.dezacodex.data.HeartRepository
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoginAuthAuditTest {

    private lateinit var context: Context
    private lateinit var db: HeartDatabase
    private lateinit var repo: HeartRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HeartDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = HeartRepository(db.heartDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `hashPassword uses unique cryptographic salt per invocation`() {
        val h1 = SecurityUtils.hashPassword("HeartSync#2026")
        val h2 = SecurityUtils.hashPassword("HeartSync#2026")
        assertThat(h1).contains(":")
        assertThat(h2).contains(":")
        assertThat(h1).isNotEqualTo(h2)
    }

    @Test
    fun `verifyPassword succeeds for correct password and fails for wrong or unsalted hash`() {
        val stored = SecurityUtils.hashPassword("StrongPass99!")
        assertThat(SecurityUtils.verifyPassword("StrongPass99!", stored)).isTrue()
        assertThat(SecurityUtils.verifyPassword("WrongPass99!", stored)).isFalse()
        assertThat(SecurityUtils.verifyPassword("plaintext", "plaintext")).isFalse()
    }

    @Test
    fun `isValidEmail validates standard addresses and rejects malformed strings`() {
        assertThat(SecurityUtils.isValidEmail("alice@heartsync.app")).isTrue()
        assertThat(SecurityUtils.isValidEmail("bob.smith+couple@gmail.com")).isTrue()
        assertThat(SecurityUtils.isValidEmail("missing-at-sign.com")).isFalse()
        assertThat(SecurityUtils.isValidEmail("bad@domain")).isFalse()
        assertThat(SecurityUtils.isValidEmail("")).isFalse()
    }

    @Test
    fun `isValidPasswordStrength enforces minimum length, letter, and digit`() {
        assertThat(SecurityUtils.isValidPasswordStrength("abc1234")).isFalse() // 7 chars
        assertThat(SecurityUtils.isValidPasswordStrength("onlyletters")).isFalse()
        assertThat(SecurityUtils.isValidPasswordStrength("123456789")).isFalse()
        assertThat(SecurityUtils.isValidPasswordStrength("HeartSync2026")).isTrue()
    }

    @Test
    fun `generateSixDigitCode produces randomized 6-digit OTPs with no static 2212 or 1234 bypass`() {
        val codes = (1..25).map { SecurityUtils.generateSixDigitCode() }.toSet()
        codes.forEach { code ->
            assertThat(code).matches("^[1-9][0-9]{5}$")
            assertThat(code).isNotEqualTo("2212")
            assertThat(code).isNotEqualTo("1234")
        }
        assertThat(codes.size).isGreaterThan(15)
    }

    @Test
    fun `generateInviteCode produces HS-prefixed 4-digit codes`() {
        val code = SecurityUtils.generateInviteCode()
        assertThat(code).matches("^HS-[1-9][0-9]{3}$")
    }

    @Test
    fun `sanitizeForLog redacts mongodb URIs and API keys`() {
        val raw = "Failed connecting mongodb+srv://user:secret@cluster.mongodb.net/db with AIzaSyDUMMYKEY123456789012345"
        val clean = SecurityUtils.sanitizeForLog(raw)
        assertThat(clean).doesNotContain("secret")
        assertThat(clean).doesNotContain("AIzaSyDUMMYKEY")
        assertThat(clean).contains("mongodb+srv://[REDACTED]")
        assertThat(clean).contains("[REDACTED_KEY]")
    }

    @Test
    fun `repository registerUser hashes password and loginUser authenticates without exposing plaintext`() = runBlocking {
        val created = repo.registerUser(
            email = "Alice@HeartSync.Test",
            username = "Alice",
            rawOrHashedPassword = "MySecretPassword1",
            phone = "+15550100"
        )
        assertThat(created.passwordHash).contains(":")
        assertThat(created.passwordHash).doesNotContain("MySecretPassword1")
        assertThat(created.isOtpVerified).isFalse()

        val validLogin = repo.loginUser("alice@heartsync.test", "MySecretPassword1")
        assertThat(validLogin).isNotNull()
        assertThat(validLogin?.username).isEqualTo("Alice")

        val badLogin = repo.loginUser("alice@heartsync.test", "2212")
        assertThat(badLogin).isNull()
    }

    @Test
    fun `googleAuthManager refuses empty credentials and exposes clean error if default_web_client_id unavailable`() = runBlocking {
        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.heartsync.lvwjzd")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key")
                    .build()
            )
        } else FirebaseApp.getInstance()
        val auth = FirebaseAuth.getInstance(app)
        val manager = GoogleAuthManager(context, auth)
        val res = manager.signInWithGoogle(context)
        assertThat(res).isInstanceOf(GoogleAuthResult.Error::class.java)
    }
}
