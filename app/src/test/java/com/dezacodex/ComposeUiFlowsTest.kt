package com.dezacodex

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.dezacodex.call.CallLayoutMode
import com.dezacodex.ui.AppScreen
import com.dezacodex.ui.HeartSyncApp
import com.dezacodex.ui.HeartViewModel
import com.dezacodex.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeUiFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    @Test
    fun `welcome screen renders auth actions and navigates to login and signup`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        composeRule.setContent {
            MyApplicationTheme {
                HeartSyncApp(viewModel = vm)
            }
        }

        composeRule.onNodeWithTag("welcome_google_button").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_signup_button").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_login_button").assertIsDisplayed()

        composeRule.onNodeWithTag("welcome_login_button").performClick()
        assertEquals(AppScreen.LOGIN, vm.currentScreen.value)
        composeRule.onNodeWithTag("login_submit_button").assertIsDisplayed()

        composeRule.onNodeWithTag("login_back_button").performClick()
        assertEquals(AppScreen.WELCOME, vm.currentScreen.value)

        composeRule.onNodeWithTag("welcome_signup_button").performClick()
        assertEquals(AppScreen.SIGNUP, vm.currentScreen.value)
    }

    @Test
    fun `signup ui flow creates user, displays sandbox otp hint, verifies, and locks tabs until paired`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }
        vm.navigateTo(AppScreen.SIGNUP)

        composeRule.setContent {
            MyApplicationTheme {
                HeartSyncApp(viewModel = vm)
            }
        }

        composeRule.onNodeWithTag("signup_name_input").performScrollTo().performTextInput("Elena")
        composeRule.onNodeWithTag("signup_email_input").performScrollTo().performTextInput("elena@ui.app")
        composeRule.onNodeWithTag("signup_password_input").performScrollTo().performTextInput("StrongPass1")
        composeRule.onNodeWithTag("signup_submit_button").performScrollTo().performClick()

        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        composeRule.waitForIdle()

        val issuedOtp = vm.pendingLocalOtpHint.value!!
        composeRule.onNodeWithText("Local Sandbox Verification Code: $issuedOtp").assertIsDisplayed()
        composeRule.onNodeWithTag("otp_code_input").performTextInput(issuedOtp)
        composeRule.onNodeWithTag("otp_verify_button").performClick()

        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        composeRule.waitForIdle()

        // Unpaired user sees Couple Sanctuary Locked on Chats tab
        composeRule.onNodeWithText("Couple Sanctuary Locked").assertIsDisplayed()
    }

    @Test
    fun `paired user sees soulmate sync banner, sends chat message, and opens discord video call room`() {
        val vm = HeartViewModel(app)
        vm.toggleOfflineSandboxMode(true)
        vm.togglePartnerSimulation(false)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.WELCOME }

        vm.handleNormalSignUp("Romeo", "romeo@ui.app", "Pass1234", "+15551234", 24, "Male")
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.OTP_VERIFY }
        vm.submitOtpCode(vm.pendingLocalOtpHint.value!!)
        TestHelpers.waitUntil { vm.currentScreen.value == AppScreen.MAIN }
        vm.inviteAndLinkCouple("L0V3P1")
        TestHelpers.waitUntil { vm.currentUser.value?.connectedPartnerEmail != null }
        vm.clearPairingCelebration()

        composeRule.setContent {
            MyApplicationTheme {
                HeartSyncApp(viewModel = vm)
            }
        }
        composeRule.waitForIdle()

        // Soulmate sync banner visible only after pairing
        composeRule.onNodeWithText("SOULMATE SYNC").assertIsDisplayed()

        // Send a chat message through Compose UI
        composeRule.onNodeWithTag("chat_message_input").performTextInput("Hello from UI test!")
        composeRule.onNodeWithTag("chat_send_button").performClick()
        TestHelpers.waitUntil { vm.messages.value.any { it.text == "Hello from UI test!" } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello from UI test!").assertIsDisplayed()

        // Switch to Calls tab and initiate Discord Video Call
        composeRule.onNodeWithTag("bottom_nav_CALLS").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("btn_start_video_call").assertIsDisplayed().performClick()

        TestHelpers.waitUntil(timeoutMs = 3000L) { vm.callUiState.value.callStatus == "CONNECTED" }
        composeRule.waitForIdle()

        // Toggle Layout Grid <-> Focus and Mute Mic in live Call UI
        composeRule.onNodeWithTag("btn_toggle_layout").assertIsDisplayed().performClick()
        assertEquals(CallLayoutMode.FOCUS, vm.callUiState.value.layoutMode)

        composeRule.onNodeWithTag("btn_toggle_mic").performClick()
        assertTrue(vm.callUiState.value.localMicMuted)

        composeRule.onNodeWithTag("btn_end_call").performClick()
        composeRule.waitForIdle()
        assertEquals("IDLE", vm.callUiState.value.callStatus)
    }
}
