package com.dezacodex.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dezacodex.R
import com.dezacodex.call.*
import com.dezacodex.data.*
import com.dezacodex.notifications.HeartNotificationHelper
import com.dezacodex.security.GoogleAuthManager
import com.dezacodex.security.GoogleAuthResult
import com.dezacodex.security.InputValidator
import com.dezacodex.security.PasswordHasher
import com.dezacodex.security.SecurityUtils
import com.dezacodex.updater.UpdateManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class AppScreen {
    SPLASH, ONBOARDING, WELCOME, LOGIN, SIGNUP, OTP_VERIFY, FORGOT_PWD, PAIRING, MAIN, ADMIN
}

class HeartViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HeartDatabase.getDatabase(application)
    private val repository = HeartRepository(database.heartDao())
    private val storageRepository by lazy { FirebaseStorageRepository(application) }

    private val firebaseAppInitialized: Boolean = try {
        if (FirebaseApp.getApps(application).isEmpty()) {
            FirebaseApp.initializeApp(application) != null
        } else true
    } catch (_: Exception) {
        false
    }

    private val firebaseAuth: FirebaseAuth? = if (firebaseAppInitialized) {
        try { FirebaseAuth.getInstance() } catch (_: Exception) { null }
    } else null

    private val firestore: FirebaseFirestore? = if (firebaseAppInitialized) {
        try {
            val dbId = application.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(dbId)
        } catch (_: Exception) { null }
    } else null

    private val firestoreRepo: FirestoreHeartRepository? =
        if (firestore != null && firebaseAuth != null) FirestoreHeartRepository(firestore, firebaseAuth) else null

    val googleAuthManager: GoogleAuthManager? =
        if (firebaseAuth != null) GoogleAuthManager(application, firebaseAuth) else null

    val callEngine = WebRtcCallEngine(
        context = application,
        repositoryProvider = { firestoreRepo },
        scope = viewModelScope
    )

    val callController = DiscordCallController(application, viewModelScope)
    val callUiState: StateFlow<CallUiState> = callController.uiState

    private val _currentScreen = MutableStateFlow(AppScreen.SPLASH)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    private val _firebaseUser = MutableStateFlow<FirebaseUser?>(firebaseAuth?.currentUser)
    val firebaseUser: StateFlow<FirebaseUser?> = _firebaseUser.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _activeCoupleRoom = MutableStateFlow<FirestoreCoupleRoom?>(null)
    val activeCoupleRoom: StateFlow<FirestoreCoupleRoom?> = _activeCoupleRoom.asStateFlow()

    private val _notifications = MutableStateFlow<List<FirestoreUserNotification>>(emptyList())
    val notifications: StateFlow<List<FirestoreUserNotification>> = _notifications.asStateFlow()

    val profile: StateFlow<LoversProfile?> = repository.profileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _messages = MutableStateFlow<List<LoveMessage>>(emptyList())
    val messages: StateFlow<List<LoveMessage>> = _messages.asStateFlow()

    private val _snaps = MutableStateFlow<List<CoupleSnap>>(emptyList())
    val snaps: StateFlow<List<CoupleSnap>> = _snaps.asStateFlow()

    private val _stories = MutableStateFlow<List<CoupleStory>>(emptyList())
    val stories: StateFlow<List<CoupleStory>> = _stories.asStateFlow()

    private val _forceOfflineMode = MutableStateFlow(false)
    val forceOfflineMode = _forceOfflineMode.asStateFlow()

    private val _pendingLocalOtpHint = MutableStateFlow<String?>(null)
    val pendingLocalOtpHint: StateFlow<String?> = _pendingLocalOtpHint.asStateFlow()

    fun toggleOfflineSandboxMode(enable: Boolean) {
        _forceOfflineMode.value = enable
        if (enable) {
            viewModelScope.launch {
                ensureSandboxDemoPartner()
            }
            if (_currentScreen.value == AppScreen.SPLASH) {
                _currentScreen.value = AppScreen.WELCOME
            }
        }
    }

    private suspend fun ensureSandboxDemoPartner() {
        val dao = database.heartDao()
        val existing = dao.getUserByEmail(SANDBOX_PARTNER_EMAIL)
        if (existing == null) {
            dao.insertUser(
                User(
                    email = SANDBOX_PARTNER_EMAIL,
                    username = "Sophia Sim",
                    passwordHash = PasswordHasher.hashPassword("SandboxPartner99"),
                    inviteCode = SANDBOX_PARTNER_CODE,
                    isOtpVerified = true,
                    age = 23,
                    gender = "Forever connected in sandbox"
                )
            )
        }
    }

    val isCloudConnected: Boolean
        get() = firebaseAuth?.currentUser != null && firestoreRepo != null && !_forceOfflineMode.value

    private val _activeViewingSnap = MutableStateFlow<CoupleSnap?>(null)
    val activeViewingSnap = _activeViewingSnap.asStateFlow()

    private val _viewingSecondsRemaining = MutableStateFlow(0)
    val viewingSecondsRemaining = _viewingSecondsRemaining.asStateFlow()

    private val _callState = MutableStateFlow("IDLE") // IDLE, OUTGOING, INCOMING, CONNECTED
    val callState = _callState.asStateFlow()

    private val _callType = MutableStateFlow("VIDEO")
    val callType = _callType.asStateFlow()

    private val _isPartnerSimulating = MutableStateFlow(false)
    val isPartnerSimulating = _isPartnerSimulating.asStateFlow()

    private val _isAutoCamSync = MutableStateFlow(true)
    val isAutoCamSync = _isAutoCamSync.asStateFlow()

    private val _cameraEnabledSelf = MutableStateFlow(true)
    val cameraEnabledSelf = _cameraEnabledSelf.asStateFlow()

    private val _cameraEnabledPartner = MutableStateFlow(true)
    val cameraEnabledPartner = _cameraEnabledPartner.asStateFlow()

    private val _forgotPasswordPhase = MutableStateFlow(1)
    val forgotPasswordPhase = _forgotPasswordPhase.asStateFlow()
    var tempForgotEmail = ""
    private var pendingOtpCode: String? = null
    private var pendingOtpExpiryMs: Long = 0L

    private val _pairingCelebrationPartnerName = MutableStateFlow<String?>(null)
    val pairingCelebrationPartnerName = _pairingCelebrationPartnerName.asStateFlow()

    private val _adminUsersList = MutableStateFlow<List<User>>(emptyList())
    val adminUsersList: StateFlow<List<User>> = _adminUsersList.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading = _isAuthLoading.asStateFlow()

    var tempPhoneVerifiedEmail: String = ""

    val appVersion = "1.0"
    private val updateManager = UpdateManager(application)

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow(0f)
    val updateDownloadProgress: StateFlow<Float> = _updateDownloadProgress.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _updateApkUrl = MutableStateFlow<String?>(null)
    val updateApkUrl: StateFlow<String?> = _updateApkUrl.asStateFlow()

    private val _updateStatusMessage = MutableStateFlow<String?>(null)
    val updateStatusMessage: StateFlow<String?> = _updateStatusMessage.asStateFlow()

    private var coupleSyncJobs: List<Job> = emptyList()
    private var firestoreRoomSyncJobs: List<Job> = emptyList()
    private val notifiedIds = mutableSetOf<String>()

    init {
        HeartNotificationHelper.ensureChannelsCreated(application)

        // Keep legacy callState/callType flows synchronized with DiscordCallController
        viewModelScope.launch {
            callController.uiState.collect { ui ->
                _callState.value = ui.callStatus
                _callType.value = ui.callType
                _cameraEnabledSelf.value = ui.localCamEnabled
            }
        }

        // Purge expired disappearing messages every 2 seconds
        viewModelScope.launch {
            while (true) {
                delay(2000)
                repository.purgeExpiredDisappearingMessages()
            }
        }

        viewModelScope.launch {
            repository.ensureProfileExists()
            delay(1800)

            if (_currentScreen.value != AppScreen.SPLASH) {
                return@launch
            }

            val fbUser = firebaseAuth?.currentUser
            if (fbUser != null && !_forceOfflineMode.value) {
                handleAuthenticatedFirebaseUser(fbUser)
            } else {
                val savedEmail = database.heartDao().getProfileDirect()?.loggedInUserEmail.orEmpty()
                val savedUser = if (savedEmail.isNotBlank()) database.heartDao().getUserByEmail(savedEmail) else null
                if (savedUser != null && savedUser.isOtpVerified && !_forceOfflineMode.value) {
                    _currentUser.value = savedUser
                    startCoupleDataSync()
                    if (savedUser.email.lowercase() in ADMIN_EMAILS) {
                        _currentScreen.value = AppScreen.ADMIN
                        loadAdminUsers()
                    } else {
                        _currentScreen.value = AppScreen.MAIN
                    }
                } else {
                    _currentScreen.value = AppScreen.WELCOME
                }
            }
        }

        // Observe live Firebase Auth state changes
        googleAuthManager?.let { manager ->
            viewModelScope.launch {
                manager.observeAuthState().collect { user ->
                    _firebaseUser.value = user
                    if (user != null && _currentScreen.value != AppScreen.SPLASH && !_forceOfflineMode.value) {
                        handleAuthenticatedFirebaseUser(user)
                    }
                }
            }
        }
    }

    private fun issueLocalVerificationOtp(): String {
        val otp = PasswordHasher.generateSecureOtp(6)
        pendingOtpCode = otp
        pendingOtpExpiryMs = System.currentTimeMillis() + 5 * 60 * 1000L
        _pendingLocalOtpHint.value = otp
        return otp
    }

    private fun clearPendingOtp() {
        pendingOtpCode = null
        pendingOtpExpiryMs = 0L
        _pendingLocalOtpHint.value = null
    }

    private fun appendLocalNotification(type: String, title: String, body: String, senderName: String) {
        val localNotif = FirestoreUserNotification(
            id = "local_${System.currentTimeMillis()}_${notifiedIds.size}",
            recipientUid = _currentUser.value?.email ?: "local",
            senderUid = senderName,
            senderName = senderName,
            coupleRoomId = _activeCoupleRoom.value?.roomId,
            type = type,
            title = title,
            body = body,
            isRead = false
        )
        notifiedIds.add(localNotif.id)
        _notifications.value = listOf(localNotif) + _notifications.value.take(49)
    }

    fun markNotificationRead(notificationId: String) {
        _notifications.value = _notifications.value.map {
            if (it.id == notificationId) it.copy(isRead = true) else it
        }
        if (isCloudConnected && !notificationId.startsWith("local_")) {
            viewModelScope.launch {
                firestoreRepo?.markNotificationRead(notificationId)
            }
        }
    }

    fun markAllNotificationsRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
        val uid = firebaseAuth?.currentUser?.uid
        if (isCloudConnected && uid != null) {
            viewModelScope.launch {
                firestoreRepo?.markAllNotificationsRead(uid)
            }
        }
    }

    private suspend fun resolveUploadedMediaUrl(folder: String, mediaUri: Uri?): String? {
        if (mediaUri == null) return null
        val uid = firebaseAuth?.currentUser?.uid
        if (uid == null || _forceOfflineMode.value) {
            return mediaUri.toString()
        }
        return when (val res = storageRepository.uploadImageUri(folder, uid, mediaUri)) {
            is StorageUploadResult.Success -> res.downloadUrl
            is StorageUploadResult.RequiresConfiguration -> mediaUri.toString()
            is StorageUploadResult.Error -> mediaUri.toString()
        }
    }

    private suspend fun handleAuthenticatedFirebaseUser(fbUser: FirebaseUser) {
        val repo = firestoreRepo ?: return
        val ensured = repo.ensureUserDocument(fbUser)
        val fsUser = ensured.getOrNull()
        val email = (fsUser?.email ?: fbUser.email ?: "${fbUser.uid}@heartsync.user").lowercase()
        val mirrorUser = User(
            email = email,
            username = fsUser?.username ?: fbUser.displayName ?: "HeartSync User",
            avatarUrl = fsUser?.photoUrl ?: fbUser.photoUrl?.toString().orEmpty(),
            phone = fsUser?.phone.orEmpty(),
            inviteCode = fsUser?.inviteCode ?: SecurityUtils.generateInviteCode(),
            connectedPartnerEmail = fsUser?.partnerEmail ?: fsUser?.partnerUsername,
            streakCount = fsUser?.streakCount ?: 0,
            snapScore = fsUser?.snapScore ?: 0,
            totalMomentsCount = fsUser?.totalMomentsCount ?: 0,
            isGoogleUser = true,
            isOtpVerified = true,
            currentTheme = fsUser?.currentTheme ?: "Vibrant Palette",
            age = fsUser?.age ?: 0,
            gender = fsUser?.bio ?: fsUser?.gender.orEmpty()
        )
        repository.upsertLocalMirrorUser(mirrorUser)
        _currentUser.value = mirrorUser
        saveSessionEmail(mirrorUser.email)

        // Register FCM token if messaging is configured
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) {
                repo.updateFcmToken(fbUser.uid, token)
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token registration skipped/unavailable: ${e.message}")
        }

        startFirestoreUserAndCoupleSync(fbUser.uid)
        startCoupleDataSync()

        if (email in ADMIN_EMAILS && fbUser.isEmailVerified) {
            _currentScreen.value = AppScreen.ADMIN
            loadAdminUsers()
        } else {
            _currentScreen.value = AppScreen.MAIN
        }
    }

    private fun startFirestoreUserAndCoupleSync(uid: String) {
        val repo = firestoreRepo ?: return
        firestoreRoomSyncJobs.forEach { it.cancel() }

        val userJob = viewModelScope.launch {
            try {
                repo.observeUser(uid).collect { fsUser ->
                    if (fsUser != null) {
                        val current = _currentUser.value
                        val updated = (current ?: User(email = fsUser.email, username = fsUser.username)).copy(
                            username = fsUser.username,
                            avatarUrl = fsUser.photoUrl.orEmpty(),
                            phone = fsUser.phone.orEmpty(),
                            inviteCode = fsUser.inviteCode,
                            connectedPartnerEmail = fsUser.partnerEmail ?: fsUser.partnerUid ?: current?.connectedPartnerEmail,
                            streakCount = fsUser.streakCount,
                            snapScore = fsUser.snapScore,
                            totalMomentsCount = fsUser.totalMomentsCount,
                            currentTheme = fsUser.currentTheme,
                            gender = fsUser.bio ?: fsUser.gender.orEmpty()
                        )
                        _currentUser.value = updated
                        repository.upsertLocalMirrorUser(updated)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "observeUser stopped: ${e.message}")
            }
        }

        val notifJob = viewModelScope.launch {
            try {
                repo.observeNotifications(uid).collect { list ->
                    _notifications.value = list
                    list.filter { !it.isRead && notifiedIds.add(it.id) }.forEach { notif ->
                        HeartNotificationHelper.showNotification(
                            context = getApplication(),
                            channelId = HeartNotificationHelper.channelForType(notif.type),
                            title = notif.title,
                            body = notif.body
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "observeNotifications stopped: ${e.message}")
            }
        }

        val roomsJob = viewModelScope.launch {
            try {
                repo.observeCoupleRoomsForCurrentUser().collect { rooms ->
                    val room = rooms.firstOrNull()
                    _activeCoupleRoom.value = room
                }
            } catch (e: Exception) {
                Log.w(TAG, "observeCoupleRooms stopped: ${e.message}")
            }
        }

        firestoreRoomSyncJobs = listOf(userJob, notifJob, roomsJob)
    }

    fun signInWithGoogleCredentialManager(activityContext: Context) {
        if (_forceOfflineMode.value) {
            triggerGoogleSignIn("sandbox.google@heartsync.app", "Sandbox Lover")
            return
        }
        val manager = googleAuthManager
        if (manager == null) {
            _authError.value = "Firebase Auth is not configured in this environment. Attach google-services.json or use Local Sandbox Mode."
            return
        }
        viewModelScope.launch {
            _isAuthLoading.value = true
            _authError.value = null
            when (val res = manager.signInWithGoogle(activityContext)) {
                is GoogleAuthResult.Success -> {
                    handleAuthenticatedFirebaseUser(res.user)
                }
                is GoogleAuthResult.Error -> {
                    if (!res.isCancellation) {
                        _authError.value = res.message
                    }
                }
            }
            _isAuthLoading.value = false
        }
    }

    fun triggerGoogleSignIn(email: String, displayName: String) {
        viewModelScope.launch {
            _authError.value = null
            val normEmail = InputValidator.normalizeEmail(email)
            if (!InputValidator.isValidEmail(normEmail)) {
                _authError.value = "Please provide a valid Google email address."
                return@launch
            }
            if (normEmail in ADMIN_EMAILS) {
                _authError.value = "Admin accounts require verified credentials and cannot use unverified Google sign-in."
                return@launch
            }
            val dao = database.heartDao()
            val existing = dao.getUserByEmail(normEmail)
            if (existing != null && !existing.isGoogleUser && existing.passwordHash.isNotBlank()) {
                _authError.value = "This email is already registered with a password. Please log in with your password."
                return@launch
            }

            val cleanName = InputValidator.sanitizeDisplayText(displayName, 60).ifBlank { "HeartSync Lover" }
            val user = existing?.copy(isGoogleUser = true, isOtpVerified = true)
                ?: User(
                    email = normEmail,
                    username = cleanName,
                    inviteCode = SecurityUtils.generateInviteCode(),
                    isGoogleUser = true,
                    isOtpVerified = true
                )
            repository.upsertLocalMirrorUser(user)
            _currentUser.value = user
            saveSessionEmail(user.email)
            startCoupleDataSync()
            _currentScreen.value = AppScreen.MAIN
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _isCheckingForUpdates.value = true
            _updateStatusMessage.value = "Checking GitHub project releases..."
            val (available, releaseUrl) = updateManager.checkForUpdate(appVersion)
            _isCheckingForUpdates.value = false
            _updateAvailable.value = available
            _updateApkUrl.value = releaseUrl
            _updateStatusMessage.value = if (available) {
                "New release found! Open release page to update."
            } else {
                "You are already using the latest version."
            }
        }
    }

    fun openUpdateReleasePage() {
        val url = _updateApkUrl.value ?: "https://github.com/DezaCodex/HeartSync-App/releases/latest"
        val opened = updateManager.openReleasePage(url)
        _updateStatusMessage.value = if (opened) {
            "Opening official release page in browser."
        } else {
            "Unable to open browser for release link."
        }
    }

    fun dismissUpdateStatus() {
        _updateStatusMessage.value = null
    }

    fun startCoupleDataSync() {
        val user = _currentUser.value ?: return
        val partnerEmail = user.connectedPartnerEmail ?: ""
        coupleSyncJobs.forEach { it.cancel() }

        val msgJob = viewModelScope.launch {
            repository.getMessagesForCouple(user.email, partnerEmail).collect { list ->
                val now = System.currentTimeMillis()
                _messages.value = list.filter {
                    !it.isDisappearing || it.isSavedInChat || (it.timestamp + it.durationSecs * 1000L) > now
                }
            }
        }
        val snapJob = viewModelScope.launch {
            repository.getSnapsForCouple(user.email, partnerEmail).collect { list ->
                _snaps.value = list
            }
        }
        val storyJob = viewModelScope.launch {
            repository.getStoriesForCouple(user.email, partnerEmail)
                .map { list ->
                    val now = System.currentTimeMillis()
                    list.filter { it.expiresAt > now || it.isSavedInChat }
                }
                .collect { list ->
                    _stories.value = list
                }
        }
        coupleSyncJobs = listOf(msgJob, snapJob, storyJob)
    }

    fun saveSessionEmail(email: String) {
        viewModelScope.launch {
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(legacy.copy(loggedInUserEmail = email))
        }
    }

    fun handleNormalLogin(email: String, pas: String) {
        viewModelScope.launch {
            _authError.value = null
            val validationError = InputValidator.validateLogin(email, pas)
            if (validationError != null) {
                _authError.value = validationError
                return@launch
            }
            val localUser = repository.loginUser(email, pas)
            if (localUser == null) {
                _authError.value = "Invalid credentials or account not registered on device."
                return@launch
            }
            if (!localUser.isOtpVerified) {
                issueLocalVerificationOtp()
                tempPhoneVerifiedEmail = localUser.email
                _currentScreen.value = AppScreen.OTP_VERIFY
                _authError.value = "Verification code required. Please confirm your account."
                return@launch
            }

            _currentUser.value = localUser
            saveSessionEmail(localUser.email)
            startCoupleDataSync()
            if (localUser.email.lowercase() in ADMIN_EMAILS) {
                _currentScreen.value = AppScreen.ADMIN
                loadAdminUsers()
            } else {
                _currentScreen.value = AppScreen.MAIN
            }
        }
    }

    fun handleNormalSignUp(name: String, email: String, pas: String, phone: String, age: Int, gender: String) {
        viewModelScope.launch {
            _authError.value = null
            val clampedAge = if (age <= 0) 21 else age
            val validationError = InputValidator.validateRegistration(name, email, pas, clampedAge)
            if (validationError != null) {
                _authError.value = validationError
                return@launch
            }
            val cleanName = InputValidator.sanitizeDisplayText(name, 60)
            val regResult = repository.registerUser(
                email = email,
                username = cleanName,
                rawPassword = pas,
                phone = phone.trim()
            )
            if (regResult.isFailure) {
                _authError.value = regResult.exceptionOrNull()?.message ?: "An account with this email already exists."
                return@launch
            }
            val created = regResult.getOrThrow().copy(
                age = clampedAge.coerceIn(13, 120),
                gender = InputValidator.sanitizeDisplayText(gender, 120)
            )
            repository.updateUser(created)
            _currentUser.value = created
            saveSessionEmail(created.email)
            tempPhoneVerifiedEmail = created.email
            issueLocalVerificationOtp()
            _currentScreen.value = AppScreen.OTP_VERIFY
            _authError.value = "Account created. Enter your 6-digit verification code."
        }
    }

    fun resendVerificationOtp() {
        issueLocalVerificationOtp()
        _authError.value = "Sent a new 6-digit verification code."
    }

    fun submitOtpCode(code: String) {
        viewModelScope.launch {
            _authError.value = null
            val email = tempPhoneVerifiedEmail.ifEmpty { _currentUser.value?.email ?: "" }
            val expected = pendingOtpCode
            if (expected.isNullOrBlank() || System.currentTimeMillis() > pendingOtpExpiryMs || code.trim() != expected) {
                _authError.value = "Incorrect or expired verification code."
                return@launch
            }
            clearPendingOtp()
            val user = database.heartDao().getUserByEmail(email.lowercase())
            if (user != null) {
                val updated = user.copy(isOtpVerified = true)
                repository.updateUser(updated)
                _currentUser.value = updated
                saveSessionEmail(updated.email)
                startCoupleDataSync()
                if (updated.email.lowercase() in ADMIN_EMAILS) {
                    _currentScreen.value = AppScreen.ADMIN
                    loadAdminUsers()
                } else {
                    _currentScreen.value = AppScreen.MAIN
                }
            } else {
                _authError.value = "Account not found. Please sign in again."
            }
        }
    }

    fun startForgotPasswordReset(email: String) {
        viewModelScope.launch {
            val normEmail = InputValidator.normalizeEmail(email)
            val user = database.heartDao().getUserByEmail(normEmail)
            if (user == null) {
                _authError.value = "No registered local account found for $email"
                return@launch
            }
            tempForgotEmail = user.email
            issueLocalVerificationOtp()
            _forgotPasswordPhase.value = 2
            _authError.value = "Password reset code generated for ${user.email}."
        }
    }

    fun verifyForgotOtp(otp: String) {
        val expected = pendingOtpCode
        if (!expected.isNullOrBlank() && System.currentTimeMillis() <= pendingOtpExpiryMs && otp.trim() == expected) {
            clearPendingOtp()
            _forgotPasswordPhase.value = 3
            _authError.value = "Code validated. Create a new strong password."
        } else {
            _authError.value = "Incorrect or expired verification code."
        }
    }

    fun submitNewPassword(newPass: String) {
        viewModelScope.launch {
            if (newPass.length < 6) {
                _authError.value = "Password must be at least 6 characters."
                return@launch
            }
            val user = database.heartDao().getUserByEmail(tempForgotEmail) ?: return@launch
            val hashed = PasswordHasher.hashPassword(newPass)
            val updated = user.copy(passwordHash = hashed, isOtpVerified = true)
            repository.updateUser(updated)
            _currentUser.value = updated
            saveSessionEmail(updated.email)
            _forgotPasswordPhase.value = 1
            startCoupleDataSync()
            _currentScreen.value = AppScreen.MAIN
        }
    }

    fun triggerPasswordReset(email: String) = startForgotPasswordReset(email)

    // -------------------------------------------------------------
    // PAIRING FLOW (CLOUD FIRESTORE INVITE CODE + LOCAL CACHE)
    // -------------------------------------------------------------
    fun inviteAndLinkCouple(code: String) {
        viewModelScope.launch {
            _authError.value = null
            val trimmed = code.trim().uppercase()
            if (trimmed == SANDBOX_PARTNER_CODE && _forceOfflineMode.value) {
                ensureSandboxDemoPartner()
            }
            val fsRepo = firestoreRepo
            if (isCloudConnected && fsRepo != null) {
                val res = fsRepo.pairWithInviteCode(trimmed)
                if (res.isSuccess) {
                    val room = res.getOrNull()
                    _activeCoupleRoom.value = room
                    val partnerTitle = room?.partnerName ?: "Partner"
                    _pairingCelebrationPartnerName.value = partnerTitle
                    HeartNotificationHelper.showNotification(
                        context = getApplication(),
                        channelId = HeartNotificationHelper.CHANNEL_STREAKS,
                        title = "Couple Paired!",
                        body = "You are now connected with $partnerTitle."
                    )
                    _currentScreen.value = AppScreen.MAIN
                    return@launch
                } else {
                    _authError.value = res.exceptionOrNull()?.localizedMessage ?: "Pairing failed."
                    return@launch
                }
            }

            val self = _currentUser.value ?: return@launch
            if (trimmed.equals(self.inviteCode.trim(), ignoreCase = true)) {
                _authError.value = "You cannot pair with your own code."
                return@launch
            }
            val partnerUser = repository.getUserByInviteCode(trimmed)
            val linked = repository.linkCouple(self.email, trimmed)
            if (linked) {
                val updatedSelf = database.heartDao().getUserByEmail(self.email) ?: self
                _currentUser.value = updatedSelf
                val partnerName = partnerUser?.username ?: "Partner"
                _pairingCelebrationPartnerName.value = partnerName
                appendLocalNotification("PAIRING", "Couple Bond Created!", "Connected with $partnerName", partnerName)
                HeartNotificationHelper.showNotification(
                    context = getApplication(),
                    channelId = HeartNotificationHelper.CHANNEL_STREAKS,
                    title = "Couple Bond Created!",
                    body = "Connected with $partnerName"
                )
                startCoupleDataSync()
                _currentScreen.value = AppScreen.MAIN
            } else {
                _authError.value = "Partner code is invalid or already paired."
            }
        }
    }

    fun generatePairingOtp() {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val digits = SecurityUtils.generateSixDigitCode()
            val expiry = System.currentTimeMillis() + 5 * 60 * 1000L
            val updated = user.copy(pairingOtp = digits, pairingOtpExpiry = expiry)
            repository.updateUser(updated)
            _currentUser.value = updated
            _authError.value = "6-digit pairing OTP generated (valid for 5 minutes)."
        }
    }

    fun connectCoupleByEmailAndOtp(partnerEmail: String, otpCode: String) {
        viewModelScope.launch {
            _authError.value = null
            val self = _currentUser.value ?: return@launch
            val trimEmail = InputValidator.normalizeEmail(partnerEmail)
            val trimOtp = otpCode.trim()

            if (!InputValidator.isValidEmail(trimEmail) || trimOtp.length < 4) {
                _authError.value = "Please enter a valid partner email and OTP code."
                return@launch
            }
            if (trimEmail == self.email.lowercase()) {
                _authError.value = "You cannot pair with your own email."
                return@launch
            }

            val partnerUser = database.heartDao().getUserByEmail(trimEmail)
            if (partnerUser == null) {
                _authError.value = "No registered partner account found for $trimEmail."
                return@launch
            }
            val expectedOtp = partnerUser.pairingOtp
            if (expectedOtp.isNullOrBlank() || expectedOtp != trimOtp) {
                _authError.value = "Invalid or incorrect OTP code."
                return@launch
            }
            if (partnerUser.pairingOtpExpiry < System.currentTimeMillis()) {
                _authError.value = "This OTP code has expired. Request a new code."
                return@launch
            }

            val updatedPartner = partnerUser.copy(
                connectedPartnerEmail = self.email,
                pairingOtp = null,
                pairingOtpExpiry = 0,
                streakCount = maxOf(1, partnerUser.streakCount),
                snapScore = partnerUser.snapScore + 15
            )
            val updatedSelf = self.copy(
                connectedPartnerEmail = trimEmail,
                streakCount = maxOf(1, self.streakCount),
                snapScore = self.snapScore + 15
            )
            repository.updateUser(updatedPartner)
            repository.updateUser(updatedSelf)

            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(
                legacy.copy(
                    myName = updatedSelf.username,
                    partnerName = partnerUser.username,
                    isCoupleLinked = true,
                    streakCount = maxOf(1, legacy.streakCount),
                    totalScore = legacy.totalScore + 15
                )
            )

            _currentUser.value = updatedSelf
            _pairingCelebrationPartnerName.value = partnerUser.username
            appendLocalNotification("PAIRING", "Couple Bond Created!", "Connected with ${partnerUser.username}", partnerUser.username)
            HeartNotificationHelper.showNotification(
                context = getApplication(),
                channelId = HeartNotificationHelper.CHANNEL_STREAKS,
                title = "Couple Bond Created!",
                body = "Connected with ${partnerUser.username}"
            )
            startCoupleDataSync()
            _currentScreen.value = AppScreen.MAIN
        }
    }

    fun removeCoupleLink() {
        viewModelScope.launch {
            val self = _currentUser.value ?: return@launch
            repository.removeConnection(self.email)
            if (isCloudConnected) {
                firebaseAuth?.currentUser?.uid?.let { uid ->
                    firestoreRepo?.updateUserFields(
                        uid,
                        mapOf("partnerUid" to "", "partnerEmail" to "", "coupleRoomId" to "")
                    )
                }
            }
            val updatedSelf = database.heartDao().getUserByEmail(self.email) ?: self
            _currentUser.value = updatedSelf
            _activeCoupleRoom.value = null
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
            callController.hangUpCall()
            callEngine.endCallSession()
            _callState.value = "IDLE"
            coupleSyncJobs.forEach { it.cancel() }
            firestoreRoomSyncJobs.forEach { it.cancel() }
            clearPendingOtp()
            googleAuthManager?.signOut()
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(legacy.copy(loggedInUserEmail = ""))
            _currentUser.value = null
            _firebaseUser.value = null
            _activeCoupleRoom.value = null
            _authError.value = null
            _currentScreen.value = AppScreen.WELCOME
        }
    }

    // -------------------------------------------------------------
    // CHAT & STREAK SYSTEM (WITH FIREBASE STORAGE MEDIA & NOTIFICATIONS)
    // -------------------------------------------------------------
    fun sendMessage(
        text: String,
        isDisappearing: Boolean = false,
        duration: Int = 10,
        msgEffect: String = "",
        mediaUri: Uri? = null
    ) {
        val user = _currentUser.value ?: return
        val cleanText = InputValidator.sanitizeDisplayText(text, 2000)
        if (cleanText.isBlank() && mediaUri == null) return
        val partnerEmail = user.connectedPartnerEmail ?: ""

        viewModelScope.launch {
            val uploadedUrl = resolveUploadedMediaUrl("chat_images", mediaUri)
            val displayBody = cleanText.ifBlank { "Shared a photo" }
            val previousStreak = user.streakCount

            repository.sendMessage(
                sender = user.username,
                text = displayBody,
                isDisappearing = isDisappearing,
                duration = duration,
                senderEmail = user.email,
                receiverEmail = partnerEmail,
                msgEffect = msgEffect,
                mediaUrl = uploadedUrl
            )
            val updatedStreak = repository.recordSnapStreakActivity(user.email)

            val room = _activeCoupleRoom.value
            val fbUid = firebaseAuth?.currentUser?.uid
            val fsRepo = firestoreRepo
            if (isCloudConnected && room != null && fbUid != null && fsRepo != null) {
                fsRepo.sendMessage(
                    roomId = room.roomId,
                    memberUids = room.memberUids,
                    senderName = user.username,
                    text = displayBody,
                    isDisappearing = isDisappearing,
                    durationSecs = duration,
                    msgEffect = msgEffect,
                    mediaUrl = uploadedUrl
                )
                val updatedUser = database.heartDao().getUserByEmail(user.email)
                fsRepo.syncCoupleStreakAndScore(
                    roomId = room.roomId,
                    uid = fbUid,
                    newStreakCount = updatedStreak,
                    newSnapScore = updatedUser?.snapScore ?: (user.snapScore + 1)
                )
                val partnerUid = room.memberUids.firstOrNull { it != fbUid }
                if (!partnerUid.isNullOrBlank()) {
                    fsRepo.pushNotificationToUser(
                        recipientUid = partnerUid,
                        coupleRoomId = room.roomId,
                        senderName = user.username,
                        type = "MESSAGE",
                        title = "${user.username} sent a message",
                        body = if (isDisappearing) "Disappearing message" else displayBody.take(120)
                    )
                    if (updatedStreak > previousStreak) {
                        fsRepo.pushNotificationToUser(
                            recipientUid = partnerUid,
                            coupleRoomId = room.roomId,
                            senderName = user.username,
                            type = "STREAK",
                            title = "Couple Streak: $updatedStreak Days!",
                            body = "${user.username} kept your streak alive ($updatedStreak days)."
                        )
                    }
                }
            } else if (updatedStreak > previousStreak) {
                appendLocalNotification(
                    type = "STREAK",
                    title = "Couple Streak: $updatedStreak Days!",
                    body = "Your daily streak increased to $updatedStreak days.",
                    senderName = user.username
                )
                HeartNotificationHelper.showNotification(
                    context = getApplication(),
                    channelId = HeartNotificationHelper.CHANNEL_STREAKS,
                    title = "Couple Streak: $updatedStreak Days!",
                    body = "Your daily streak increased to $updatedStreak days."
                )
            }

            val updatedSelf = database.heartDao().getUserByEmail(user.email) ?: user
            _currentUser.value = updatedSelf
        }
    }

    fun reactToMessage(messageId: Int, reaction: String) {
        viewModelScope.launch {
            repository.updateMessageReaction(messageId, reaction)
            val user = _currentUser.value ?: return@launch
            val room = _activeCoupleRoom.value
            val fbUid = firebaseAuth?.currentUser?.uid
            val fsRepo = firestoreRepo
            if (isCloudConnected && room != null && fbUid != null && fsRepo != null) {
                val partnerUid = room.memberUids.firstOrNull { it != fbUid }
                if (!partnerUid.isNullOrBlank()) {
                    fsRepo.pushNotificationToUser(
                        recipientUid = partnerUid,
                        coupleRoomId = room.roomId,
                        senderName = user.username,
                        type = "MESSAGE",
                        title = "${user.username} reacted $reaction",
                        body = "${user.username} reacted $reaction to a message"
                    )
                }
            }
        }
    }

    fun saveMessageToChat(messageId: Int, pin: Boolean) {
        viewModelScope.launch {
            repository.saveMessageInChat(messageId, pin)
        }
    }

    fun deleteMessage(id: Int) {
        viewModelScope.launch { repository.deleteMessage(id) }
    }

    fun clearChat() {
        val user = _currentUser.value
        val partnerEmail = user?.connectedPartnerEmail.orEmpty()
        viewModelScope.launch {
            if (user != null && partnerEmail.isNotBlank()) {
                repository.clearChatForCouple(user.email, partnerEmail)
            } else {
                repository.clearChat()
            }
        }
    }

    // -------------------------------------------------------------
    // SNAPS & SCREENSHOT ALERTS (WITH FIREBASE STORAGE MEDIA)
    // -------------------------------------------------------------
    fun sendSnap(snapType: String, description: String, durationSecs: Int = 10, mediaUri: Uri? = null) {
        val user = _currentUser.value ?: return
        val partnerEmail = user.connectedPartnerEmail ?: ""
        val cleanDesc = InputValidator.sanitizeDisplayText(description, 500)
        if (cleanDesc.isBlank() && mediaUri == null) return

        viewModelScope.launch {
            val uploadedUrl = resolveUploadedMediaUrl("snaps", mediaUri)
            val previousStreak = user.streakCount
            repository.sendSnap(
                sender = user.username,
                snapType = snapType,
                description = cleanDesc.ifBlank { "Snap Photo" },
                durationSec = durationSecs,
                senderEmail = user.email,
                receiverEmail = partnerEmail,
                mediaUrl = uploadedUrl
            )
            val updatedStreak = repository.recordSnapStreakActivity(user.email)

            val room = _activeCoupleRoom.value
            val fbUid = firebaseAuth?.currentUser?.uid
            val fsRepo = firestoreRepo
            if (isCloudConnected && room != null && fbUid != null && fsRepo != null) {
                fsRepo.sendSnap(
                    roomId = room.roomId,
                    memberUids = room.memberUids,
                    senderName = user.username,
                    snapType = snapType,
                    description = cleanDesc.ifBlank { "Snap Photo" },
                    mediaUrl = uploadedUrl,
                    durationSec = durationSecs
                )
                val partnerUid = room.memberUids.firstOrNull { it != fbUid }
                if (!partnerUid.isNullOrBlank()) {
                    fsRepo.pushNotificationToUser(
                        recipientUid = partnerUid,
                        coupleRoomId = room.roomId,
                        senderName = user.username,
                        type = "SNAP",
                        title = "New Snap from ${user.username}",
                        body = cleanDesc.ifBlank { "Tap to view disappearing snap" }.take(100)
                    )
                    if (updatedStreak > previousStreak) {
                        fsRepo.pushNotificationToUser(
                            recipientUid = partnerUid,
                            coupleRoomId = room.roomId,
                            senderName = user.username,
                            type = "STREAK",
                            title = "Snap Streak: $updatedStreak Days!",
                            body = "${user.username} boosted your Snap streak to $updatedStreak days."
                        )
                    }
                }
            }
            _currentUser.value = database.heartDao().getUserByEmail(user.email) ?: user
        }
    }

    fun openSnap(snap: CoupleSnap) {
        if (snap.viewed && !snap.isSavedInChat) return
        val user = _currentUser.value
        viewModelScope.launch {
            _activeViewingSnap.value = snap
            _viewingSecondsRemaining.value = snap.durationSec
            val newStreak = repository.viewSnapAndTriggerStreak(snap.id, user?.email)
            user?.email?.let { email ->
                _currentUser.value = database.heartDao().getUserByEmail(email)
            }
            appendLocalNotification(
                type = "STREAK",
                title = "Streak Updated!",
                body = "Your couple streak is now ${maxOf(1, newStreak)} days.",
                senderName = snap.senderName
            )
            HeartNotificationHelper.showNotification(
                context = getApplication(),
                channelId = HeartNotificationHelper.CHANNEL_STREAKS,
                title = "Streak Updated!",
                body = "Your couple streak is now ${maxOf(1, newStreak)} days."
            )
            while (_viewingSecondsRemaining.value > 0 && _activeViewingSnap.value?.id == snap.id) {
                delay(1000)
                _viewingSecondsRemaining.value -= 1
            }
            if (_activeViewingSnap.value?.id == snap.id) {
                _activeViewingSnap.value = null
            }
        }
    }

    fun triggerSimulatedScreenshot(snapId: Int) {
        viewModelScope.launch {
            repository.alertScreenshot(snapId)
            val user = _currentUser.value ?: return@launch
            val partnerEmail = user.connectedPartnerEmail ?: ""
            repository.sendMessage(
                sender = "SYSTEM",
                text = "${user.username} captured a screenshot of your Snap.",
                isDisappearing = false,
                senderEmail = user.email,
                receiverEmail = partnerEmail
            )
            val room = _activeCoupleRoom.value
            val fbUid = firebaseAuth?.currentUser?.uid
            val fsRepo = firestoreRepo
            if (isCloudConnected && room != null && fbUid != null && fsRepo != null) {
                val partnerUid = room.memberUids.firstOrNull { it != fbUid }
                if (!partnerUid.isNullOrBlank()) {
                    fsRepo.pushNotificationToUser(
                        recipientUid = partnerUid,
                        coupleRoomId = room.roomId,
                        senderName = user.username,
                        type = "SCREENSHOT",
                        title = "Screenshot Alert",
                        body = "${user.username} took a screenshot of your Snap."
                    )
                }
            }
            appendLocalNotification(
                type = "SCREENSHOT",
                title = "Screenshot Alert",
                body = "${user.username} took a screenshot of a Snap.",
                senderName = user.username
            )
            HeartNotificationHelper.showNotification(
                context = getApplication(),
                channelId = HeartNotificationHelper.CHANNEL_SNAPS,
                title = "Screenshot Alert",
                body = "${user.username} took a screenshot of a Snap."
            )
        }
    }

    fun saveSnapInChat(snapId: Int, pinned: Boolean) {
        viewModelScope.launch { repository.saveSnapToChat(snapId, pinned) }
    }

    fun downloadSnapToLocalStorage(snapId: Int) {
        viewModelScope.launch {
            val targetSnap = _snaps.value.firstOrNull { it.id == snapId }
            val ext = if (!targetSnap?.mediaUrl.isNullOrBlank()) "jpg" else "txt"
            val result = storageRepository.saveMediaToDeviceDownloads(
                filename = "heartsync_snap_$snapId.$ext",
                mediaUrl = targetSnap?.mediaUrl,
                fallbackTextSummary = "HeartSync Snap #$snapId\nFrom: ${targetSnap?.senderName ?: "Partner"}\nCaption: ${targetSnap?.description ?: ""}"
            )
            if (result.isSuccess) {
                val realPath = result.getOrThrow()
                repository.downloadSnapToLocal(snapId, realPath)
                _authError.value = "Snap saved to /Download: $realPath"
            } else {
                _authError.value = "Unable to save snap to device storage."
            }
        }
    }

    fun closeActiveSnap() {
        _activeViewingSnap.value = null
    }

    fun deleteSnap(snapId: Int) {
        viewModelScope.launch { repository.deleteSnap(snapId) }
    }

    // -------------------------------------------------------------
    // STORIES / MOMENTS (WITH FIREBASE STORAGE MEDIA & NOTIFICATIONS)
    // -------------------------------------------------------------
    fun shareStory(description: String, category: String, mediaUri: Uri? = null) {
        val user = _currentUser.value ?: return
        val cleanDesc = InputValidator.sanitizeDisplayText(description, 500)
        if (cleanDesc.isBlank() && mediaUri == null) return

        viewModelScope.launch {
            val uploadedUrl = resolveUploadedMediaUrl("stories", mediaUri)
            repository.shareStory(
                sender = user.username,
                description = cleanDesc.ifBlank { "Shared a moment" },
                imageType = category,
                senderEmail = user.email,
                mediaUrl = uploadedUrl
            )
            val room = _activeCoupleRoom.value
            val fbUid = firebaseAuth?.currentUser?.uid
            val fsRepo = firestoreRepo
            if (isCloudConnected && room != null && fbUid != null && fsRepo != null) {
                fsRepo.sendStory(
                    roomId = room.roomId,
                    memberUids = room.memberUids,
                    senderName = user.username,
                    description = cleanDesc.ifBlank { "Shared a moment" },
                    imageType = category,
                    mediaUrl = uploadedUrl
                )
                val partnerUid = room.memberUids.firstOrNull { it != fbUid }
                if (!partnerUid.isNullOrBlank()) {
                    fsRepo.pushNotificationToUser(
                        recipientUid = partnerUid,
                        coupleRoomId = room.roomId,
                        senderName = user.username,
                        type = "STORY",
                        title = "${user.username} shared a new Moment",
                        body = cleanDesc.ifBlank { "Tap Moments to view" }.take(120)
                    )
                }
            }
            _currentUser.value = database.heartDao().getUserByEmail(user.email) ?: user
        }
    }

    fun saveStoryToHighlights(storyId: Int, pin: Boolean) {
        viewModelScope.launch {
            repository.saveStoryToChat(storyId, pin)
            _authError.value = if (pin) "Saved to highlights album!" else "Removed from highlights."
        }
    }

    fun downloadStoryToLocalStorage(storyId: Int) {
        viewModelScope.launch {
            val story = _stories.value.firstOrNull { it.id == storyId }
            val ext = if (!story?.mediaUrl.isNullOrBlank()) "jpg" else "txt"
            val result = storageRepository.saveMediaToDeviceDownloads(
                filename = "heartsync_story_$storyId.$ext",
                mediaUrl = story?.mediaUrl,
                fallbackTextSummary = "HeartSync Story #$storyId\nFrom: ${story?.senderName ?: "Partner"}\nCategory: ${story?.imageType ?: ""}\nNote: ${story?.description ?: ""}"
            )
            if (result.isSuccess) {
                val path = result.getOrThrow()
                repository.downloadStoryToLocal(storyId, path)
                _authError.value = "Story saved to Local Gallery (/Download): $path"
            } else {
                _authError.value = "Failed exporting story to device storage."
            }
        }
    }

    // -------------------------------------------------------------
    // PROFILE SETTINGS, THEMES & FIREBASE STORAGE AVATAR UPLOAD
    // -------------------------------------------------------------
    fun updateProfileName(myNewName: String, partnerNewName: String, anniversary: String) {
        val user = _currentUser.value ?: return
        val cleanMyName = InputValidator.sanitizeDisplayText(myNewName, 60)
        if (cleanMyName.length < 2) {
            _authError.value = "Please enter a valid name (at least 2 characters)."
            return
        }
        viewModelScope.launch {
            val u = user.copy(username = cleanMyName)
            repository.updateUser(u)
            _currentUser.value = u
            if (isCloudConnected) {
                firebaseAuth?.currentUser?.uid?.let { uid ->
                    firestoreRepo?.updateUserFields(uid, mapOf("username" to cleanMyName, "anniversaryDate" to anniversary))
                }
            }
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(
                legacy.copy(myName = cleanMyName, partnerName = partnerNewName, anniversaryDate = anniversary)
            )
        }
    }

    fun updateTheme(newTheme: String) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val u = user.copy(currentTheme = newTheme)
            repository.updateUser(u)
            _currentUser.value = u
            if (isCloudConnected) {
                firebaseAuth?.currentUser?.uid?.let { uid ->
                    firestoreRepo?.updateUserFields(uid, mapOf("currentTheme" to newTheme))
                }
            }
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(legacy.copy(currentTheme = newTheme))
        }
    }

    fun uploadAndSaveAvatar(uri: Uri) {
        val uid = firebaseAuth?.currentUser?.uid
        if (uid == null || _forceOfflineMode.value) {
            updateAvatarUrl(uri.toString())
            return
        }
        viewModelScope.launch {
            when (val res = storageRepository.uploadImageUri("avatars", uid, uri)) {
                is StorageUploadResult.Success -> {
                    updateAvatarUrl(res.downloadUrl)
                    _authError.value = "Avatar uploaded to Firebase Storage!"
                }
                is StorageUploadResult.RequiresConfiguration -> {
                    updateAvatarUrl(uri.toString())
                    _authError.value = res.reason
                }
                is StorageUploadResult.Error -> {
                    updateAvatarUrl(uri.toString())
                    _authError.value = "Saved locally; cloud bucket error: ${res.message}"
                }
            }
        }
    }

    fun updateAvatarUrl(url: String) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val u = user.copy(avatarUrl = url)
            repository.updateUser(u)
            _currentUser.value = u
            if (isCloudConnected) {
                firebaseAuth?.currentUser?.uid?.let { uid ->
                    firestoreRepo?.updateUserFields(uid, mapOf("photoUrl" to url.take(1024)))
                }
            }
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(legacy.copy(avatarUrl = url))
        }
    }

    fun updateUserProfileDetails(
        newName: String,
        newEmail: String,
        newPhone: String,
        newAge: Int,
        newBio: String,
        newAvatarUrl: String
    ) {
        val user = _currentUser.value ?: return
        val cleanName = InputValidator.sanitizeDisplayText(newName, 60)
        if (cleanName.length < 2) {
            _authError.value = "Please enter a valid name (at least 2 characters)."
            return
        }
        val cleanNewEmail = InputValidator.normalizeEmail(newEmail)
        if (!InputValidator.isValidEmail(cleanNewEmail)) {
            _authError.value = "Please enter a valid email address."
            return
        }
        val clampedAge = newAge.coerceIn(13, 120)
        val cleanBio = InputValidator.sanitizeDisplayText(newBio, 240)
        val cleanPhone = InputValidator.sanitizeDisplayText(newPhone, 32)
        val cleanAvatar = newAvatarUrl.trim().take(1024)

        viewModelScope.launch {
            val oldEmail = user.email
            val updatedUser = user.copy(
                username = cleanName,
                email = cleanNewEmail,
                phone = cleanPhone,
                age = clampedAge,
                gender = cleanBio,
                avatarUrl = cleanAvatar
            )
            if (oldEmail.lowercase() != cleanNewEmail) {
                repository.updateUser(updatedUser)
                database.heartDao().deleteUserByEmail(oldEmail)
                user.connectedPartnerEmail?.let { pEmail ->
                    database.heartDao().getUserByEmail(pEmail)?.let { partner ->
                        repository.updateUser(partner.copy(connectedPartnerEmail = cleanNewEmail))
                    }
                }
                saveSessionEmail(cleanNewEmail)
            } else {
                repository.updateUser(updatedUser)
            }
            _currentUser.value = updatedUser

            if (isCloudConnected) {
                firebaseAuth?.currentUser?.uid?.let { uid ->
                    firestoreRepo?.updateUserFields(
                        uid,
                        mapOf(
                            "username" to cleanName,
                            "phone" to cleanPhone,
                            "age" to clampedAge,
                            "bio" to cleanBio,
                            "photoUrl" to cleanAvatar
                        )
                    )
                }
            }

            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(
                legacy.copy(
                    myName = cleanName,
                    loggedInUserEmail = cleanNewEmail,
                    avatarUrl = cleanAvatar,
                    statusText = cleanBio
                )
            )
        }
    }

    fun connectCoupleByEmailAndCode(partnerEmail: String, partnerCode: String) {
        viewModelScope.launch {
            _authError.value = null
            if (isCloudConnected && firestoreRepo != null) {
                inviteAndLinkCouple(partnerCode)
                return@launch
            }
            val self = _currentUser.value ?: return@launch
            val trimEmail = InputValidator.normalizeEmail(partnerEmail)
            val trimCode = partnerCode.trim().uppercase()

            if (trimEmail.isEmpty() || trimCode.isEmpty()) {
                _authError.value = "Please complete both email and exchange code fields."
                return@launch
            }
            if (trimEmail == self.email.lowercase()) {
                _authError.value = "You cannot pair with your own email!"
                return@launch
            }
            val partnerUser = database.heartDao().getUserByEmail(trimEmail)
            if (partnerUser == null) {
                _authError.value = "No registered user found with the email: $trimEmail"
                return@launch
            }
            val cleanPartnerInvite = partnerUser.inviteCode.replace("HS-", "").trim().uppercase()
            val cleanEnteredCode = trimCode.replace("HS-", "").trim().uppercase()
            if (cleanPartnerInvite != cleanEnteredCode) {
                _authError.value = "Exchange code is incorrect."
                return@launch
            }
            if (partnerUser.connectedPartnerEmail != null && partnerUser.connectedPartnerEmail != self.email) {
                _authError.value = "Your partner is already paired with another account."
                return@launch
            }
            if (repository.linkCouple(self.email, partnerUser.inviteCode)) {
                val updatedSelf = database.heartDao().getUserByEmail(self.email) ?: self
                _currentUser.value = updatedSelf
                _pairingCelebrationPartnerName.value = partnerUser.username
                startCoupleDataSync()
                _currentScreen.value = AppScreen.MAIN
            } else {
                _authError.value = "Connection failed. Please retry."
            }
        }
    }

    fun updateStatusText(status: String) {
        val cleanStatus = InputValidator.sanitizeDisplayText(status, 240)
        viewModelScope.launch {
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(legacy.copy(statusText = cleanStatus))
            val self = _currentUser.value
            if (self != null) {
                val updated = self.copy(gender = cleanStatus)
                repository.updateUser(updated)
                _currentUser.value = updated
            }
            if (isCloudConnected) {
                firebaseAuth?.currentUser?.uid?.let { uid ->
                    firestoreRepo?.updateUserFields(uid, mapOf("bio" to cleanStatus))
                }
            }
        }
    }

    // -------------------------------------------------------------
    // CALLS: DISCORD-STYLE MULTI-STREAM WEBRTC + NOTIFICATIONS
    // -------------------------------------------------------------
    fun updateRingtoneSettings(
        notificationTone: String,
        callRingtone: String,
        videoCallRingtone: String,
        vibration: String
    ) {
        viewModelScope.launch {
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(
                legacy.copy(
                    notificationTone = notificationTone,
                    callRingtone = callRingtone,
                    videoCallRingtone = videoCallRingtone,
                    vibrationIntensity = vibration
                )
            )
            _authError.value = "Tones & vibration updated!"
        }
    }

    fun updateVoiceWallpaper(wallpaper: String) {
        viewModelScope.launch {
            val legacy = database.heartDao().getProfileDirect() ?: LoversProfile()
            repository.updateProfile(legacy.copy(voiceCallWallpaper = wallpaper))
            _authError.value = "Call background updated to: $wallpaper"
        }
    }

    fun toggleMic() {
        callController.toggleMicrophone()
        callEngine.toggleMute()
    }

    fun toggleCameraSelf() {
        callController.toggleCamera()
        _cameraEnabledSelf.value = callEngine.toggleCamera()
    }

    fun toggleCameraPartner() {
        _cameraEnabledPartner.value = !_cameraEnabledPartner.value
    }

    fun setAutoCamSync(enabled: Boolean) {
        _isAutoCamSync.value = enabled
        callEngine.setAutoCamSwitchEnabled(enabled)
    }

    fun startScreenShare(data: Intent, captureAudio: Boolean = true) {
        callController.startScreenShare(data, captureAudio)
        callEngine.startScreenSharing(data, shareScreenAudio = captureAudio)
    }

    fun stopScreenShare() {
        callController.stopScreenShare()
        callEngine.stopScreenSharing()
    }

    fun switchCallCamera(cameraId: String? = null) {
        callController.switchCamera(cameraId)
        callEngine.switchCamera()
    }

    fun setCallLayoutMode(mode: CallLayoutMode) {
        callController.setLayoutMode(mode)
        callEngine.setLayoutMode(mode)
    }

    fun setCallFocusTarget(target: FocusTarget, participantId: String? = null) {
        callController.setFocusTarget(target, participantId)
    }

    fun togglePinParticipant(participantId: String) {
        callController.togglePinParticipant(participantId)
        callEngine.togglePinParticipant(participantId)
    }

    fun toggleHideNonVideo() {
        callController.toggleHideNonVideoParticipants()
        callEngine.setHideNonVideoParticipants(callController.uiState.value.hideNonVideoParticipants)
    }

    fun selectAudioOutput(output: AudioOutputDevice) {
        callController.selectAudioOutput(output)
    }

    fun setVadSensitivity(sensitivity: VadSensitivity) {
        callController.setVadSensitivity(sensitivity)
    }

    fun setVideoQualityPreset(preset: VideoQualityPreset) {
        callController.setVideoQualityPreset(preset)
    }

    fun clearPairingCelebration() {
        _pairingCelebrationPartnerName.value = null
    }

    fun initiateCall(type: String) {
        val user = _currentUser.value
        val room = _activeCoupleRoom.value
        if (user == null || (user.connectedPartnerEmail.isNullOrEmpty() && room == null)) {
            _authError.value = "Only connected couples can call. Please pair with your partner first."
            return
        }
        val partnerDisplay = room?.partnerName
            ?: user.connectedPartnerEmail?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
            ?: "Partner"
        val roomKey = room?.roomId ?: "local_call_${System.currentTimeMillis()}"

        callController.startOutgoingCall(
            callType = type,
            roomId = roomKey,
            localUserEmail = user.email,
            localUserName = user.username,
            partnerDisplayName = partnerDisplay
        )

        viewModelScope.launch {
            val myUid = firebaseAuth?.currentUser?.uid ?: user.email
            val partnerUid = room?.memberUids?.firstOrNull { it != myUid } ?: user.connectedPartnerEmail
            val members = room?.memberUids ?: listOfNotNull(myUid, partnerUid)

            val createdCallRoom = if (isCloudConnected && firestoreRepo != null) {
                firestoreRepo.createCallRoom(
                    coupleRoomId = room?.roomId,
                    memberUids = members,
                    callType = type,
                    title = "${user.username} & $partnerDisplay Stage"
                ).getOrNull()
            } else null

            val callRoomId = createdCallRoom?.roomId ?: roomKey
            if (isCloudConnected && partnerUid != null && room != null) {
                firestoreRepo?.pushNotificationToUser(
                    recipientUid = partnerUid,
                    coupleRoomId = room.roomId,
                    senderName = user.username,
                    type = "CALL",
                    title = "Incoming $type Call",
                    body = "${user.username} started a multi-stream $type call"
                )
            }
            appendLocalNotification(
                type = "CALL",
                title = "HeartSync $type Call",
                body = "Calling $partnerDisplay...",
                senderName = user.username
            )
            HeartNotificationHelper.showNotification(
                context = getApplication(),
                channelId = HeartNotificationHelper.CHANNEL_CALLS,
                title = "HeartSync $type Call",
                body = "Connecting multi-stream WebRTC room..."
            )
            callEngine.startCallSession(
                callRoomId = callRoomId,
                myUid = myUid,
                myDisplayName = user.username,
                partnerUid = partnerUid,
                partnerDisplayName = partnerDisplay,
                callType = type
            )
        }
    }

    fun receiveSimulatedCall(type: String) {
        val user = _currentUser.value
        val room = _activeCoupleRoom.value
        if (user == null || (user.connectedPartnerEmail.isNullOrEmpty() && room == null)) {
            _authError.value = "Pair with your partner first to test incoming call alerts."
            return
        }
        val partnerDisplay = room?.partnerName
            ?: user.connectedPartnerEmail?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
            ?: "Partner"
        callController.receiveIncomingCall(
            callType = type,
            roomId = room?.roomId ?: "sim_call_room",
            localUserEmail = user.email,
            localUserName = user.username,
            callerDisplayName = partnerDisplay
        )
        appendLocalNotification(
            type = "CALL",
            title = "Incoming $type Call",
            body = "$partnerDisplay is calling on HeartSync",
            senderName = partnerDisplay
        )
        HeartNotificationHelper.showNotification(
            context = getApplication(),
            channelId = HeartNotificationHelper.CHANNEL_CALLS,
            title = "Incoming $type Call",
            body = "$partnerDisplay is calling on HeartSync"
        )
    }

    fun acceptIncomingCall() {
        val user = _currentUser.value ?: return
        val room = _activeCoupleRoom.value
        val myUid = firebaseAuth?.currentUser?.uid ?: user.email
        val partnerUid = room?.memberUids?.firstOrNull { it != myUid } ?: user.connectedPartnerEmail
        callController.acceptIncomingCall()
        callEngine.startCallSession(
            callRoomId = "call_${room?.roomId ?: "pair"}",
            myUid = myUid,
            myDisplayName = user.username,
            partnerUid = partnerUid,
            partnerDisplayName = room?.partnerName ?: "Partner",
            callType = _callType.value
        )
    }

    fun hangUpCall() {
        callController.hangUpCall()
        callEngine.endCallSession()
        _callState.value = "IDLE"
    }

    fun togglePartnerSimulation(enabled: Boolean) {
        _isPartnerSimulating.value = enabled
    }

    fun loadAdminUsers() {
        val caller = _currentUser.value
        if (caller == null || caller.email.lowercase() !in ADMIN_EMAILS) {
            return
        }
        viewModelScope.launch {
            _adminUsersList.value = repository.getAllUsers()
        }
    }

    fun adminDeleteUser(email: String) {
        val caller = _currentUser.value
        if (caller == null || caller.email.lowercase() !in ADMIN_EMAILS) {
            _authError.value = "Unauthorized: Admin privileges required."
            return
        }
        val target = InputValidator.normalizeEmail(email)
        if (target in ADMIN_EMAILS) {
            _authError.value = "Cannot delete primary admin account."
            return
        }
        viewModelScope.launch {
            repository.deleteUserAccount(target)
            loadAdminUsers()
            _authError.value = "Successfully removed user record $target."
        }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        callController.hangUpCall()
        callEngine.cleanupAllMedia()
    }

    companion object {
        private const val TAG = "HeartViewModel"
        const val ADMIN_EMAIL = "dezacodex@gmail.com"
        val ADMIN_EMAILS = setOf("dezacodex@gmail.com", "rdccpscollege@gmail.com")
        private const val SANDBOX_PARTNER_EMAIL = "sophia.sim@heartsync.app"
        private const val SANDBOX_PARTNER_CODE = "L0V3P1"
    }
}
