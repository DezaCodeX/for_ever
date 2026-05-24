package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.random.Random

enum class AppScreen {
    SPLASH, ONBOARDING, WELCOME, LOGIN, SIGNUP, OTP_VERIFY, FORGOT_PWD, PAIRING, MAIN, ADMIN
}

class HeartViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HeartDatabase.getDatabase(application)
    private val repository = HeartRepository(database.heartDao())

    // App Navigation Flow Screen
    private val _currentScreen = MutableStateFlow(AppScreen.SPLASH)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    // Current Session State
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // Couple Session Details
    val profile: StateFlow<LoversProfile?> = repository.profileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Couple-specific messaging flows (dynamic according to session)
    private val _messages = MutableStateFlow<List<LoveMessage>>(emptyList())
    val messages: StateFlow<List<LoveMessage>> = _messages.asStateFlow()

    private val _snaps = MutableStateFlow<List<CoupleSnap>>(emptyList())
    val snaps: StateFlow<List<CoupleSnap>> = _snaps.asStateFlow()

    private val _stories = MutableStateFlow<List<CoupleStory>>(emptyList())
    val stories: StateFlow<List<CoupleStory>> = _stories.asStateFlow()

    val isSupabaseConnected = repository.isSupabaseConnected()
    val supabaseUrl = repository.supabaseUrl

    // ACTIVE DRAWING/VIEW VIEW STATE
    private val _activeViewingSnap = MutableStateFlow<CoupleSnap?>(null)
    val activeViewingSnap = _activeViewingSnap.asStateFlow()

    private val _viewingSecondsRemaining = MutableStateFlow(0)
    val viewingSecondsRemaining = _viewingSecondsRemaining.asStateFlow()

    // Simulated Call State
    private val _callState = MutableStateFlow("IDLE") // "IDLE", "OUTGOING", "INCOMING", "CONNECTED"
    val callState = _callState.asStateFlow()

    private val _callType = MutableStateFlow("VIDEO") // "VIDEO", "VOICE"
    val callType = _callType.asStateFlow()

    private val _isPartnerSimulating = MutableStateFlow(true)
    val isPartnerSimulating = _isPartnerSimulating.asStateFlow()

    // Drag-and-drop / Telegram-style video elements
    private val _isAutoCamSync = MutableStateFlow(true)
    val isAutoCamSync = _isAutoCamSync.asStateFlow()

    private val _cameraEnabledSelf = MutableStateFlow(true)
    val cameraEnabledSelf = _cameraEnabledSelf.asStateFlow()

    private val _cameraEnabledPartner = MutableStateFlow(true)
    val cameraEnabledPartner = _cameraEnabledPartner.asStateFlow()

    // Forgot Password OTP flow
    private val _forgotPasswordPhase = MutableStateFlow(1) // 1: Email, 2: OTP, 3: New Password
    val forgotPasswordPhase = _forgotPasswordPhase.asStateFlow()
    var tempForgotEmail = ""

    // Pairing Celebration Notification
    private val _pairingCelebrationPartnerName = MutableStateFlow<String?>(null)
    val pairingCelebrationPartnerName = _pairingCelebrationPartnerName.asStateFlow()

    // Admin Specific Lists
    private val _adminUsersList = MutableStateFlow<List<User>>(emptyList())
    val adminUsersList: StateFlow<List<User>> = _adminUsersList.asStateFlow()

    // Error & OTP States
    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()

    var tempPhoneVerifiedEmail: String = "" // Hold verification state

    init {
        // Run initial loading and splash timer - WhatsApp style persistence loader
        viewModelScope.launch {
            repository.ensureProfileExists()
            delay(2200) // Beautiful splash visual showcase delay
            
            // Check if there is an active persistent logged-in user
            val legacy = repository.profileFlow.first()
            val savedEmail = legacy?.loggedInUserEmail ?: ""
            if (savedEmail.isNotEmpty()) {
                val dbUser = database.heartDao().getUserByEmail(savedEmail)
                if (dbUser != null) {
                    _currentUser.value = dbUser
                    if (dbUser.connectedPartnerEmail == null) {
                        _currentScreen.value = AppScreen.PAIRING
                    } else {
                        startCoupleDataSync()
                        _currentScreen.value = AppScreen.MAIN
                    }
                    return@launch
                }
            }
            // Direct to onboarding if first launch, otherwise welcome
            _currentScreen.value = AppScreen.ONBOARDING
        }
    }

    // Dynamic database loader for the coupled user pairs
    fun startCoupleDataSync() {
        val user = _currentUser.value ?: return
        val partnerEmail = user.connectedPartnerEmail ?: ""
        
        viewModelScope.launch {
            // Load messages
            repository.getMessagesForCouple(user.email, partnerEmail)
                .collect { list -> _messages.value = list }
        }
        viewModelScope.launch {
            // Load snaps
            repository.getSnapsForCouple(user.email, partnerEmail)
                .collect { list -> _snaps.value = list }
        }
        viewModelScope.launch {
            // Load stories (disappearing automatically after 24 hrs matches Snapchat)
            repository.getStoriesForCouple(user.email, partnerEmail)
                .map { list -> 
                    // Filter offline local 24-hr Snapchat expiration (or let users view historic)
                    val now = System.currentTimeMillis()
                    list.filter { it.expiresAt > now || it.isSavedInChat }
                }
                .collect { list -> 
                    if (list.isEmpty() && partnerEmail.isNotEmpty()) {
                        // Seed high-fidelity moment if empty
                        repository.shareStory("Sophia", "Walking under cherry blossoms today! 🌸", "MEMORIES", "sophia@heartsync.app")
                        repository.shareStory("Alex", "Best gelato with my love 🍦❤️", "ROMANCE", "alex@heartsync.app")
                    }
                    _stories.value = list 
                }
        }
    }

    // -------------------------------------------------------------
    // AUTHENTICATION FLOWS (GOOGLE OAUTH, PASSWORD, SMS/OTP)
    // -------------------------------------------------------------
    fun saveSessionEmail(email: String) {
        viewModelScope.launch {
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            repository.updateProfile(legacy.copy(loggedInUserEmail = email))
        }
    }

    fun triggerGoogleSignIn(email: String, name: String) {
        viewModelScope.launch {
            _authError.value = null
            val user = repository.registerUser(email, name, "oauth_password", "", true)
            _currentUser.value = user
            saveSessionEmail(user.email)
            
            // Check admin status first
            if (user.email == "dezacodex@gmail.com") {
                _currentScreen.value = AppScreen.ADMIN
                loadAdminUsers()
            } else {
                startCoupleDataSync()
                _currentScreen.value = AppScreen.MAIN
            }
        }
    }

    fun handleNormalLogin(email: String, pas: String) {
        viewModelScope.launch {
            _authError.value = null
            val user = repository.loginUser(email, pas)
            if (user != null) {
                if (!user.isOtpVerified) {
                    tempPhoneVerifiedEmail = user.email
                    _currentScreen.value = AppScreen.OTP_VERIFY
                    _authError.value = "Your account requires confirmation. Please enter verification PIN OTP (e.g. 2212 or 1234) 🔑"
                    return@launch
                }
                
                _currentUser.value = user
                saveSessionEmail(user.email)
                
                // Route to appropriate screen (directly with MAIN as requested)
                if (user.email == "dezacodex@gmail.com") {
                    _currentScreen.value = AppScreen.ADMIN
                    loadAdminUsers()
                } else {
                    startCoupleDataSync()
                    _currentScreen.value = AppScreen.MAIN
                }
            } else {
                _authError.value = "Invalid credentials. If admin, use dezacodex@gmail.com / Mohan2212@"
            }
        }
    }

    fun handleNormalSignUp(name: String, email: String, pas: String, phone: String, age: Int, gender: String) {
        viewModelScope.launch {
            _authError.value = null
            if (name.isEmpty() || email.isEmpty() || pas.isEmpty()) {
                _authError.value = "Please fill in all details"
                return@launch
            }
            // Register user in the database
            val newUser = repository.registerUser(
                email = email,
                username = name,
                passwordHash = pas,
                phone = phone,
                isGoogleUser = false,
                age = age,
                gender = gender
            )
            _currentUser.value = newUser
            saveSessionEmail(newUser.email)
            tempPhoneVerifiedEmail = newUser.email
            
            // Go to OTP verification screen for account confirmation
            _currentScreen.value = AppScreen.OTP_VERIFY
            _authError.value = "Sanctuary account designed successfully! Standard safety OTP '2212' or '1234' is required to confirm your account 🔑🌸"
        }
    }

    fun submitOtpCode(code: String) {
        viewModelScope.launch {
            if (code == "2212" || code == "1234") { // Developer testing codes
                val email = tempPhoneVerifiedEmail.ifEmpty { _currentUser.value?.email ?: "" }
                val user = repository.getUserFlow(email).first()
                if (user != null) {
                    val updated = user.copy(isOtpVerified = true)
                    repository.updateUser(updated)
                    _currentUser.value = updated
                    saveSessionEmail(updated.email)
                    
                    startCoupleDataSync()
                    _currentScreen.value = AppScreen.MAIN
                    _authError.value = "Sanctuary account confirmed! Welcome home! 🌸"
                }
            } else {
                _authError.value = "Incorrect OTP. Try entering '2212' or '1234' for simulator."
            }
        }
    }

    // Interactive Forgot Password Flow with OTP & Password Updating
    fun startForgotPasswordReset(email: String) {
        viewModelScope.launch {
            val user = repository.getUserFlow(email).first()
            if (user == null) {
                _authError.value = "No registered account found with email ID: $email"
                return@launch
            }
            tempForgotEmail = email
            _forgotPasswordPhase.value = 2 // Go to OTP verification
            _authError.value = "Forgot Password OTP key simulated and dispatched to $email! (Use '2212' or '1234')"
        }
    }

    fun verifyForgotOtp(otp: String) {
        if (otp == "2212" || otp == "1234") {
            _forgotPasswordPhase.value = 3 // Go to new password input
            _authError.value = "OTP Code validated! Create your new secure password."
        } else {
            _authError.value = "Incorrect Forgot OTP pin. Try '2212' or '1234' to bypass."
        }
    }

    fun submitNewPassword(newPass: String) {
        viewModelScope.launch {
            if (newPass.length < 4) {
                _authError.value = "Password must be at least 4 characters."
                return@launch
            }
            val email = tempForgotEmail
            val user = repository.getUserFlow(email).first()
            if (user != null) {
                val updated = user.copy(passwordHash = newPass, isOtpVerified = true)
                repository.updateUser(updated)
                _currentUser.value = updated
                saveSessionEmail(updated.email)
                
                _forgotPasswordPhase.value = 1 // Reset
                _authError.value = "Password recovered successfully! You are now logged in securely."
                
                startCoupleDataSync()
                _currentScreen.value = AppScreen.MAIN
            }
        }
    }

    fun triggerPasswordReset(email: String) {
        startForgotPasswordReset(email)
    }

    // -------------------------------------------------------------
    // PAIRING FLOW (SECRET CODE CONNECTING AS COUPLE)
    // -------------------------------------------------------------
    fun inviteAndLinkCouple(code: String) {
        viewModelScope.launch {
            _authError.value = null
            val self = _currentUser.value ?: return@launch
            
            if (code == self.inviteCode) {
                _authError.value = "You cannot pair with your own code!"
                return@launch
            }

            val partnerUser = repository.getUserByInviteCode(code)
            val linked = repository.linkCouple(self.email, code)
            if (linked) {
                // Refresh self session
                val updatedSelf = repository.getUserFlow(self.email).first()
                _currentUser.value = updatedSelf
                _pairingCelebrationPartnerName.value = partnerUser?.username ?: "Your Soulmate"
                startCoupleDataSync()
                _currentScreen.value = AppScreen.MAIN
            } else {
                _authError.value = "Partner code is invalid or already connected to another soulmate."
            }
        }
    }

    fun removeCoupleLink() {
        viewModelScope.launch {
            val self = _currentUser.value ?: return@launch
            repository.removeConnection(self.email)
            val updatedSelf = repository.getUserFlow(self.email).first()
            _currentUser.value = updatedSelf
            _messages.value = emptyList()
            _snaps.value = emptyList()
            _stories.value = emptyList()
            _pairingCelebrationPartnerName.value = null
            _currentScreen.value = AppScreen.PAIRING
        }
    }

    fun skipOnboarding() {
        _currentScreen.value = AppScreen.WELCOME
    }

    fun logout() {
        viewModelScope.launch {
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            repository.updateProfile(legacy.copy(loggedInUserEmail = ""))
            _currentUser.value = null
            _authError.value = null
            _currentScreen.value = AppScreen.WELCOME
        }
    }

    // -------------------------------------------------------------
    // CHAT SYSTEM CONTROLLER
    // -------------------------------------------------------------
    fun sendMessage(text: String, isDisappearing: Boolean = false, duration: Int = 10, msgEffect: String = "") {
        val user = _currentUser.value ?: return
        val partnerEmail = user.connectedPartnerEmail ?: ""
        
        viewModelScope.launch {
            // Send actual user-to-user private message
            repository.sendMessage(
                sender = user.username,
                text = text,
                isDisappearing = isDisappearing,
                duration = duration,
                senderEmail = user.email,
                receiverEmail = partnerEmail,
                msgEffect = msgEffect
            )
            
            // Increment streaks count / score dynamically
            val updatedSelf = repository.getUserFlow(user.email).first()
            _currentUser.value = updatedSelf
            
            // Simulate partner responses
            if (_isPartnerSimulating.value && partnerEmail.isNotEmpty()) {
                delay(1500)
                generateSimulatedResponse(text, partnerEmail)
            }
        }
    }

    fun reactToMessage(messageId: Int, reaction: String) {
        viewModelScope.launch {
            repository.updateMessageReaction(messageId, reaction)
        }
    }

    fun saveMessageToChat(messageId: Int, pin: Boolean) {
        viewModelScope.launch {
            repository.saveMessageInChat(messageId, pin)
        }
    }

    fun deleteMessage(id: Int) {
        viewModelScope.launch {
            repository.deleteMessage(id)
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.clearChat()
        }
    }

    // -------------------------------------------------------------
    // SNAP SYSTEM CONTROLLER
    // -------------------------------------------------------------
    fun sendSnap(snapType: String, description: String, durationSecs: Int = 10) {
        val user = _currentUser.value ?: return
        val partnerEmail = user.connectedPartnerEmail ?: ""
        
        viewModelScope.launch {
            repository.sendSnap(
                sender = user.username,
                snapType = snapType,
                description = description,
                durationSec = durationSecs,
                senderEmail = user.email,
                receiverEmail = partnerEmail
            )
            // Restore score
            val updatedSelf = repository.getUserFlow(user.email).first()
            _currentUser.value = updatedSelf

            // Partner simulator returns snap
            if (_isPartnerSimulating.value && partnerEmail.isNotEmpty()) {
                delay(2000)
                val responseItems = listOf(
                    "Send you a sweet selfie with strawberry filters! 🍓",
                    "Cute greeting video blowing kisses! 💋📹",
                    "A picture of my comfy mug coffee! ☕"
                )
                repository.sendSnap(
                    sender = "Sophia",
                    snapType = if (Random.nextBoolean()) "IMAGE" else "VIDEO",
                    description = responseItems.random(),
                    durationSec = 10,
                    senderEmail = partnerEmail,
                    receiverEmail = user.email
                )
            }
        }
    }

    fun openSnap(snap: CoupleSnap) {
        if (snap.viewed) return
        viewModelScope.launch {
            _activeViewingSnap.value = snap
            _viewingSecondsRemaining.value = snap.durationSec
            repository.viewSnapAndTriggerStreak(snap.id)

            while (_viewingSecondsRemaining.value > 0) {
                delay(1000)
                _viewingSecondsRemaining.value -= 1
            }
            _activeViewingSnap.value = null
        }
    }

    fun triggerSimulatedScreenshot(snapId: Int) {
        viewModelScope.launch {
            repository.alertScreenshot(snapId)
            val user = _currentUser.value ?: return@launch
            val partnerEmail = user.connectedPartnerEmail ?: ""
            repository.sendMessage(
                sender = "SYSTEM 🚨",
                text = "${user.username} took a screenshot of the Snap! 🔥📸",
                isDisappearing = false,
                senderEmail = "system@heartsync.app",
                receiverEmail = partnerEmail
            )
        }
    }

    fun saveSnapInChat(snapId: Int, pinned: Boolean) {
        viewModelScope.launch {
            repository.saveSnapToChat(snapId, pinned)
        }
    }

    fun downloadSnapToLocalStorage(snapId: Int) {
        viewModelScope.launch {
            repository.downloadSnapToLocal(snapId, "/storage/emulated/0/Download/heartsync_snap_${snapId}.jpg")
            _authError.value = "Moment saved to Local Storage under /Download folder! 📁💾"
        }
    }

    fun closeActiveSnap() {
        _activeViewingSnap.value = null
    }

    fun deleteSnap(snapId: Int) {
        viewModelScope.launch {
            repository.deleteSnap(snapId)
        }
    }

    // -------------------------------------------------------------
    // SOCIAL STORIES / MOMENTS MODULE
    // -------------------------------------------------------------
    fun shareStory(description: String, category: String) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            repository.shareStory(
                sender = user.username,
                description = description,
                imageType = category,
                senderEmail = user.email
            )
            val updatedSelf = repository.getUserFlow(user.email).first()
            _currentUser.value = updatedSelf
        }
    }

    fun saveStoryToHighlights(storyId: Int, pin: Boolean) {
        viewModelScope.launch {
            repository.saveStoryToChat(storyId, pin)
            _authError.value = if (pin) "Saved to highlights album! 💖" else "Removed from highlights."
        }
    }

    fun downloadStoryToLocalStorage(storyId: Int) {
        viewModelScope.launch {
            repository.downloadStoryToLocal(storyId, "/storage/emulated/0/Download/heartsync_story_${storyId}.jpg")
            _authError.value = "Story saved to Local Gallery device storage! 📸🖼️"
        }
    }

    // -------------------------------------------------------------
    // PROFILE SETTINGS & CUSTOM LAYOUT THEMES
    // -------------------------------------------------------------
    fun updateProfileName(myNewName: String, partnerNewName: String, anniversary: String) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            // Update custom user properties
            val u = user.copy(username = myNewName)
            repository.updateUser(u)
            _currentUser.value = u

            // Update legacy LoversProfile
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            repository.updateProfile(
                legacy.copy(
                    myName = myNewName,
                    partnerName = partnerNewName,
                    anniversaryDate = anniversary
                )
            )
        }
    }

    fun updateTheme(newTheme: String) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val u = user.copy(currentTheme = newTheme)
            repository.updateUser(u)
            _currentUser.value = u

            // Update legacy LoversProfile theme
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            repository.updateProfile(legacy.copy(currentTheme = newTheme))
        }
    }

    fun updateStatusText(status: String) {
        viewModelScope.launch {
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            repository.updateProfile(legacy.copy(statusText = status))
        }
    }

    // -------------------------------------------------------------
    // PRIVATE CONNECTION CALLS BOOTH SIMULATOR
    // -------------------------------------------------------------
    fun updateRingtoneSettings(
        notificationTone: String,
        callRingtone: String,
        videoCallRingtone: String,
        vibration: String
    ) {
        viewModelScope.launch {
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            val updated = legacy.copy(
                notificationTone = notificationTone,
                callRingtone = callRingtone,
                videoCallRingtone = videoCallRingtone,
                vibrationIntensity = vibration
            )
            repository.updateProfile(updated)
            _authError.value = "WhatsApp-style tones & vibration updated successfully! 🎵📳"
        }
    }

    fun updateVoiceWallpaper(wallpaper: String) {
        viewModelScope.launch {
            val legacy = repository.profileFlow.first() ?: LoversProfile()
            repository.updateProfile(legacy.copy(voiceCallWallpaper = wallpaper))
            _authError.value = "Voice call background updated to: $wallpaper 🌅"
        }
    }

    fun toggleCameraSelf() {
        _cameraEnabledSelf.value = !_cameraEnabledSelf.value
        if (_isAutoCamSync.value) {
            _cameraEnabledPartner.value = _cameraEnabledSelf.value
        }
    }

    fun toggleCameraPartner() {
        _cameraEnabledPartner.value = !_cameraEnabledPartner.value
        if (_isAutoCamSync.value) {
            _cameraEnabledSelf.value = _cameraEnabledPartner.value
        }
    }

    fun setAutoCamSync(enabled: Boolean) {
        _isAutoCamSync.value = enabled
        if (enabled) {
            _cameraEnabledPartner.value = _cameraEnabledSelf.value
        }
    }

    fun clearPairingCelebration() {
        _pairingCelebrationPartnerName.value = null
    }

    fun initiateCall(type: String) {
        val user = _currentUser.value
        if (user == null || user.connectedPartnerEmail.isNullOrEmpty()) {
            _authError.value = "Only connected couples can call! Please pair with your partner first. 🔒♥︎"
            return
        }
        _callState.value = "OUTGOING"
        _callType.value = type
        viewModelScope.launch {
            delay(2500)
            _callState.value = "CONNECTED"
            // Ensure camera on both is on if enabled and video call
            if (type == "VIDEO" && _isAutoCamSync.value) {
                _cameraEnabledSelf.value = true
                _cameraEnabledPartner.value = true
            }
        }
    }

    fun receiveSimulatedCall(type: String) {
        val user = _currentUser.value
        if (user == null || user.connectedPartnerEmail.isNullOrEmpty()) {
            _authError.value = "Only connected couples can receive call! 🔒♥︎"
            return
        }
        _callState.value = "INCOMING"
        _callType.value = type
    }

    fun acceptIncomingCall() {
        _callState.value = "CONNECTED"
        if (_callType.value == "VIDEO" && _isAutoCamSync.value) {
            _cameraEnabledSelf.value = true
            _cameraEnabledPartner.value = true
        }
    }

    fun hangUpCall() {
        _callState.value = "IDLE"
    }

    fun togglePartnerSimulation(enabled: Boolean) {
        _isPartnerSimulating.value = enabled
    }

    // -------------------------------------------------------------
    // MODERATOR / ADMIN DASHBOARD CAPABILITIES
    // -------------------------------------------------------------
    fun loadAdminUsers() {
        viewModelScope.launch {
            _adminUsersList.value = repository.getAllUsers()
        }
    }

    fun adminDeleteUser(email: String) {
        viewModelScope.launch {
            repository.deleteUserAccount(email)
            loadAdminUsers()
            _authError.value = "Successfully removed user $email and disconnected partners!"
        }
    }

    // -------------------------------------------------------------
    // PARTNER AUTOMATED REPLY BOT (SNAPCHAT STYLED EXPERIENCES)
    // -------------------------------------------------------------
    private suspend fun generateSimulatedResponse(inputText: String, partnerEmail: String) {
        val user = _currentUser.value ?: return
        val lower = inputText.lowercase()
        val textResponse = when {
            lower.contains("hello") || lower.contains("hi") -> "Hey sweetheart! Hope you're having an amazing day 💖"
            lower.contains("love") -> "I love you more than words can describe! Forever and always 💕"
            lower.contains("streak") || lower.contains("snap") -> "Keeping our streak glowing is my favorite thing! Here is a virtual tight hug 🤗"
            lower.contains("call") -> {
                receiveSimulatedCall("VIDEO")
                "Calling you right now my love! Answer below! 📞✨"
            }
            lower.contains("heart") -> "You have my whole heart! ❤️✨"
            lower.contains("promise") -> "I swear to stay by your side through every single moment 🔒💘"
            else -> listOf(
                "Thinking of you makes my heart skip a beat 🥰",
                "You are my absolute favorite person in the entire world 🌎❤️",
                "Can't wait until we hold hands again! 🌸",
                "Sending you millions of sweet thoughts right now 🌟",
                "You make everything so much brighter ☀️💖"
            ).random()
        }
        repository.sendMessage(
            sender = "Sophia",
            text = textResponse,
            isDisappearing = false,
            senderEmail = partnerEmail,
            receiverEmail = user.email
        )
    }

    fun clearAuthError() {
        _authError.value = null
    }
}
