package com.dezacodex.data

import com.dezacodex.security.PasswordHasher
import com.dezacodex.security.SecurityUtils
import kotlinx.coroutines.flow.Flow

/**
 * Local Room cache repository backing offline caching and secondary UI synchronization.
 * Cloud truth lives in FirestoreHeartRepository + FirebaseStorageRepository.
 */
class HeartRepository(private val dao: HeartDao) {

    fun isCloudBackendReady(): Boolean = true

    suspend fun ensureProfileExists() {
        val existing = dao.getProfileDirect()
        if (existing == null) {
            dao.updateProfile(LoversProfile())
        }
    }

    fun getUserFlow(email: String): Flow<User?> = dao.getUserByEmailFlow(email)

    suspend fun registerUser(
        email: String,
        username: String,
        rawOrHashedPassword: String,
        phone: String = "",
        isGoogleUser: Boolean = false,
        age: Int = 0,
        gender: String = ""
    ): User {
        val normalizedEmail = email.trim().lowercase()
        val existing = dao.getUserByEmail(normalizedEmail)
        if (existing != null) return existing

        val hashed = when {
            isGoogleUser || rawOrHashedPassword.isEmpty() -> ""
            rawOrHashedPassword.startsWith("pbkdf2$") || rawOrHashedPassword.contains(":") -> rawOrHashedPassword
            else -> SecurityUtils.hashPassword(rawOrHashedPassword)
        }

        val user = User(
            email = normalizedEmail,
            username = username.trim(),
            passwordHash = hashed,
            phone = phone.trim(),
            inviteCode = SecurityUtils.generateInviteCode(),
            isGoogleUser = isGoogleUser,
            isOtpVerified = isGoogleUser,
            age = age,
            gender = gender.trim()
        )
        dao.insertUser(user)
        return user
    }

    suspend fun registerUser(
        email: String,
        username: String,
        rawPassword: String,
        phone: String
    ): Result<User> {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email is required."))
        }
        val existing = dao.getUserByEmail(normalizedEmail)
        if (existing != null) {
            return Result.failure(IllegalStateException("An account with this email already exists."))
        }
        val hashed = PasswordHasher.hashPassword(rawPassword)
        val user = User(
            email = normalizedEmail,
            username = username.trim(),
            passwordHash = hashed,
            phone = phone.trim(),
            inviteCode = SecurityUtils.generateInviteCode(),
            isGoogleUser = false,
            isOtpVerified = false
        )
        dao.insertUser(user)
        return Result.success(user)
    }

    suspend fun upsertLocalMirrorUser(user: User) {
        dao.insertUser(user)
    }

    suspend fun loginUser(email: String, rawPassword: String): User? {
        val normalizedEmail = email.trim().lowercase()
        val user = dao.getUserByEmail(normalizedEmail) ?: return null
        val stored = user.passwordHash
        if (stored.isBlank() || rawPassword.isEmpty()) return null

        return when {
            PasswordHasher.isHashed(stored) -> {
                if (PasswordHasher.verifyPassword(rawPassword, stored)) user else null
            }
            stored.contains(":") -> {
                if (SecurityUtils.verifyPassword(rawPassword, stored)) user else null
            }
            else -> {
                // Legacy plaintext record: verify constant-time and transparently upgrade to salted PBKDF2
                if (PasswordHasher.verifyPassword(rawPassword, stored)) {
                    val upgraded = user.copy(passwordHash = PasswordHasher.hashPassword(rawPassword))
                    dao.insertUser(upgraded)
                    upgraded
                } else {
                    null
                }
            }
        }
    }

    suspend fun getUserByInviteCode(code: String): User? = dao.getUserByInviteCode(code.trim().uppercase())

    suspend fun updateUser(user: User) {
        dao.insertUser(user)
    }

    suspend fun linkCouple(myEmail: String, partnerCode: String): Boolean {
        val mine = dao.getUserByEmail(myEmail.trim().lowercase()) ?: return false
        val partner = dao.getUserByInviteCode(partnerCode.trim().uppercase()) ?: return false
        if (mine.email == partner.email) return false
        if (partner.connectedPartnerEmail != null && partner.connectedPartnerEmail != mine.email) {
            return false
        }

        val updatedMine = mine.copy(connectedPartnerEmail = partner.email, streakCount = maxOf(1, mine.streakCount))
        val updatedPartner = partner.copy(connectedPartnerEmail = mine.email, streakCount = maxOf(1, partner.streakCount))
        dao.insertUser(updatedMine)
        dao.insertUser(updatedPartner)

        val profileDirect = dao.getProfileDirect() ?: LoversProfile()
        dao.updateProfile(
            profileDirect.copy(
                myName = mine.username,
                partnerName = partner.username,
                streakCount = maxOf(1, profileDirect.streakCount),
                totalScore = profileDirect.totalScore + 15
            )
        )
        return true
    }

    suspend fun removeConnection(myEmail: String) {
        val mine = dao.getUserByEmail(myEmail.trim().lowercase()) ?: return
        val partnerEmail = mine.connectedPartnerEmail
        dao.insertUser(mine.copy(connectedPartnerEmail = null))
        if (partnerEmail != null) {
            val partner = dao.getUserByEmail(partnerEmail)
            if (partner != null) {
                dao.insertUser(partner.copy(connectedPartnerEmail = null))
            }
        }
    }

    val profileFlow: Flow<LoversProfile?> = dao.getProfileFlow()

    suspend fun updateProfile(profile: LoversProfile) {
        dao.updateProfile(profile)
    }

    suspend fun incrementSnapScore(points: Int) {
        val current = dao.getProfileDirect() ?: LoversProfile()
        dao.updateProfile(
            current.copy(
                totalScore = current.totalScore + points,
                lastInteractionTime = System.currentTimeMillis()
            )
        )
    }

    suspend fun incrementUserScore(email: String, points: Int) {
        val user = dao.getUserByEmail(email.trim().lowercase()) ?: return
        dao.insertUser(user.copy(snapScore = user.snapScore + points))
    }

    /**
     * Synchronizes streak count across both LoversProfile and the active User entity (fixing FUNC-03).
     */
    suspend fun recordSnapStreakActivity(userEmail: String? = null): Int {
        val current = dao.getProfileDirect() ?: LoversProfile()
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        val diff = now - current.lastInteractionTime

        val newStreak = when {
            current.streakCount <= 0 -> 1
            diff in oneDayMs until (2 * oneDayMs) -> current.streakCount + 1
            diff >= (2 * oneDayMs) -> 1
            else -> current.streakCount
        }

        val updatedProfile = current.copy(
            streakCount = newStreak,
            totalScore = current.totalScore + 1,
            lastInteractionTime = now
        )
        dao.updateProfile(updatedProfile)

        val targetEmail = userEmail ?: current.loggedInUserEmail
        if (targetEmail.isNotBlank()) {
            val user = dao.getUserByEmail(targetEmail.trim().lowercase())
            if (user != null) {
                dao.insertUser(
                    user.copy(
                        streakCount = newStreak,
                        snapScore = user.snapScore + 1
                    )
                )
            }
        }
        return newStreak
    }

    val allMessages: Flow<List<LoveMessage>> = dao.getAllMessagesFlow()

    fun getMessagesForCouple(myEmail: String, partnerEmail: String): Flow<List<LoveMessage>> {
        return dao.getCoupleMessagesFlow(myEmail, partnerEmail)
    }

    suspend fun sendMessage(
        sender: String,
        text: String,
        isDisappearing: Boolean = false,
        duration: Int = 10,
        senderEmail: String = "",
        receiverEmail: String = "",
        msgEffect: String = "",
        mediaUrl: String? = null
    ) {
        val msg = LoveMessage(
            senderName = sender,
            senderEmail = senderEmail,
            receiverEmail = receiverEmail,
            text = text,
            mediaUrl = mediaUrl,
            isDisappearing = isDisappearing,
            durationSecs = duration,
            msgEffect = msgEffect
        )
        dao.insertMessage(msg)
        incrementSnapScore(1)
        if (senderEmail.isNotEmpty()) {
            incrementUserScore(senderEmail, 1)
        }
    }

    suspend fun purgeExpiredDisappearingMessages(nowMs: Long = System.currentTimeMillis()): Int {
        val currentMsgs = dao.getAllMessagesDirect()
        var deleted = 0
        for (msg in currentMsgs) {
            if (msg.isDisappearing && !msg.isSavedInChat) {
                val expiresAt = msg.timestamp + (msg.durationSecs * 1000L)
                if (nowMs >= expiresAt) {
                    dao.deleteMessageById(msg.id)
                    deleted++
                }
            }
        }
        return deleted
    }

    suspend fun updateMessageReaction(id: Int, reaction: String) {
        dao.updateMessageReaction(id, reaction)
    }

    suspend fun saveMessageInChat(id: Int, save: Boolean) {
        dao.updateMessageSaved(id, save)
    }

    suspend fun deleteMessage(id: Int) {
        dao.deleteMessageById(id)
    }

    suspend fun clearChat() {
        dao.clearAllMessages()
    }

    suspend fun clearChatForCouple(myEmail: String, partnerEmail: String) {
        dao.clearCoupleMessages(myEmail, partnerEmail)
    }

    val allSnaps: Flow<List<CoupleSnap>> = dao.getAllSnapsFlow()

    fun getSnapsForCouple(myEmail: String, partnerEmail: String): Flow<List<CoupleSnap>> {
        return dao.getCoupleSnapsFlow(myEmail, partnerEmail)
    }

    suspend fun sendSnap(
        sender: String,
        snapType: String,
        description: String,
        durationSec: Int = 10,
        senderEmail: String = "",
        receiverEmail: String = "",
        mediaUrl: String? = null
    ) {
        val exp = System.currentTimeMillis() + (durationSec * 1000L)
        val snap = CoupleSnap(
            senderName = sender,
            senderEmail = senderEmail,
            receiverEmail = receiverEmail,
            snapType = snapType,
            description = description,
            mediaUrl = mediaUrl,
            durationSec = durationSec,
            expiresAt = exp,
            viewed = false
        )
        dao.insertSnap(snap)

        val points = if (snapType == "VIDEO") 2 else 1
        incrementSnapScore(points)
        if (senderEmail.isNotEmpty()) {
            incrementUserScore(senderEmail, points)
        }
    }

    suspend fun viewSnapAndTriggerStreak(snapId: Int, viewerEmail: String? = null): Int {
        dao.markSnapAsViewed(snapId, System.currentTimeMillis())
        return recordSnapStreakActivity(viewerEmail)
    }

    suspend fun alertScreenshot(snapId: Int) {
        dao.alertScreenshot(snapId)
    }

    suspend fun saveSnapToChat(snapId: Int, saved: Boolean) {
        dao.updateSnapSaved(snapId, saved)
    }

    suspend fun downloadSnapToLocal(snapId: Int, path: String) {
        dao.updateSnapLocalStorage(snapId, path)
    }

    suspend fun deleteSnap(snapId: Int) {
        dao.deleteSnapById(snapId)
    }

    val allStories: Flow<List<CoupleStory>> = dao.getAllStoriesFlow()

    fun getStoriesForCouple(myEmail: String, partnerEmail: String): Flow<List<CoupleStory>> {
        return dao.getCoupleStoriesFlow(myEmail, partnerEmail)
    }

    suspend fun shareStory(
        sender: String,
        description: String,
        imageType: String,
        senderEmail: String = "",
        mediaUrl: String? = null
    ) {
        val story = CoupleStory(
            senderName = sender,
            senderEmail = senderEmail,
            description = description,
            imageType = imageType,
            mediaUrl = mediaUrl
        )
        dao.insertStory(story)
        incrementSnapScore(3)
        if (senderEmail.isNotEmpty()) {
            incrementUserScore(senderEmail, 3)
            val u = dao.getUserByEmail(senderEmail)
            if (u != null) {
                dao.insertUser(u.copy(totalMomentsCount = u.totalMomentsCount + 1))
            }
        }
    }

    suspend fun saveStoryToChat(storyId: Int, saved: Boolean) {
        dao.updateStorySaved(storyId, saved)
    }

    suspend fun downloadStoryToLocal(storyId: Int, path: String) {
        dao.updateStoryLocalStorage(storyId, path)
    }

    suspend fun deleteStory(storyId: Int) {
        dao.deleteStoryById(storyId)
    }

    suspend fun getAllUsers(): List<User> = dao.getAllUsersDirect()

    suspend fun deleteUserAccount(email: String) {
        dao.deleteUserByEmail(email)
    }
}
