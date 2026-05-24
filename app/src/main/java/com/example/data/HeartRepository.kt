package com.example.data

import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlin.random.Random

class HeartRepository(private val dao: HeartDao) {

    // Fetch env values injected by the platform from user secrets
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY

    // Determine the active connection status safely
    fun isSupabaseConnected(): Boolean {
        return supabaseUrl.isNotEmpty() && 
               supabaseUrl != "YOUR_SUPABASE_PROJECT_URL" && 
               supabaseAnonKey.isNotEmpty() && 
               supabaseAnonKey != "YOUR_SUPABASE_PUBLIC_ANON_KEY"
    }

    // Initialize default profile and seed default admin on first run
    suspend fun ensureProfileExists() {
        val existing = dao.getProfileDirect()
        if (existing == null) {
            dao.updateProfile(LoversProfile())
        }
        
        // Seed default admin and couple user if absent
        if (dao.getUserByEmail("dezacodex@gmail.com") == null) {
            dao.insertUser(
                User(
                    email = "dezacodex@gmail.com",
                    username = "DezaCodex Admin",
                    passwordHash = "Mohan2212@",
                    phone = "+11234567890",
                    inviteCode = "ADM1N1",
                    isOtpVerified = true
                )
            )
        }
        
        // Seed initial simulation users so they can couple-link
        if (dao.getUserByEmail("sophia@heartsync.app") == null) {
            dao.insertUser(
                User(
                    email = "sophia@heartsync.app",
                    username = "Sophia",
                    passwordHash = "sophia123",
                    phone = "+15550199",
                    inviteCode = "L0V3P1",
                    isOtpVerified = true
                )
            )
        }
    }

    // -------------------------------------------------------------
    // USER ACCOUNT OPERATIONS (AUTHENTICATION & PAIRING)
    // -------------------------------------------------------------
    fun getUserFlow(email: String): Flow<User?> = dao.getUserByEmailFlow(email)
    
    suspend fun registerUser(
        email: String,
        username: String,
        passwordHash: String,
        phone: String = "",
        isGoogleUser: Boolean = false,
        age: Int = 0,
        gender: String = ""
    ): User {
        val existing = dao.getUserByEmail(email)
        if (existing != null) return existing

        // Generate clean unique invite code
        val randomDigits = (1000..9999).random()
        val code = "HS-$randomDigits"
        
        val user = User(
            email = email,
            username = username,
            passwordHash = passwordHash,
            phone = phone,
            inviteCode = code,
            isGoogleUser = isGoogleUser,
            isOtpVerified = isGoogleUser, // Google users are verified automatically, normal signups require OTP verification
            age = age,
            gender = gender
        )
        dao.insertUser(user)
        return user
    }

    suspend fun loginUser(email: String, passwordHash: String): User? {
        val user = dao.getUserByEmail(email)
        if (user != null && user.passwordHash == passwordHash) {
            return user
        }
        return null
    }

    suspend fun getUserByInviteCode(code: String): User? {
        return dao.getUserByInviteCode(code)
    }

    suspend fun updateUser(user: User) {
        dao.insertUser(user)
    }

    suspend fun linkCouple(myEmail: String, partnerCode: String): Boolean {
        val mine = dao.getUserByEmail(myEmail) ?: return false
        val partner = dao.getUserByInviteCode(partnerCode) ?: return false
        
        // Ensure partner isn't already coupled with someone else, or link them back
        if (partner.connectedPartnerEmail != null && partner.connectedPartnerEmail != myEmail) {
            return false
        }
        
        // Link both users together
        val updatedMine = mine.copy(connectedPartnerEmail = partner.email)
        val updatedPartner = partner.copy(connectedPartnerEmail = mine.email)
        
        dao.insertUser(updatedMine)
        dao.insertUser(updatedPartner)
        
        // Legacy LoversProfile support synchronizer
        val profileDirect = dao.getProfileDirect() ?: LoversProfile()
        dao.updateProfile(
            profileDirect.copy(
                myName = mine.username,
                partnerName = partner.username,
                streakCount = 1,
                totalScore = 15
            )
        )
        return true
    }

    suspend fun removeConnection(myEmail: String) {
        val mine = dao.getUserByEmail(myEmail) ?: return
        val partnerEmail = mine.connectedPartnerEmail
        
        val updatedMine = mine.copy(connectedPartnerEmail = null)
        dao.insertUser(updatedMine)
        
        if (partnerEmail != null) {
            val partner = dao.getUserByEmail(partnerEmail)
            if (partner != null) {
                val updatedPartner = partner.copy(connectedPartnerEmail = null)
                dao.insertUser(updatedPartner)
            }
        }
    }

    // -------------------------------------------------------------
    // LEGACY & PROFILE SYNC FLOWS
    // -------------------------------------------------------------
    val profileFlow: Flow<LoversProfile?> = dao.getProfileFlow()

    suspend fun updateProfile(profile: LoversProfile) {
        dao.updateProfile(profile)
    }

    suspend fun incrementSnapScore(points: Int) {
        val current = dao.getProfileDirect() ?: LoversProfile()
        val updated = current.copy(
            totalScore = current.totalScore + points,
            lastInteractionTime = System.currentTimeMillis()
        )
        dao.updateProfile(updated)
    }

    suspend fun incrementUserScore(email: String, points: Int) {
        val user = dao.getUserByEmail(email) ?: return
        dao.insertUser(user.copy(snapScore = user.snapScore + points))
    }

    suspend fun recordSnapStreakActivity() {
        val current = dao.getProfileDirect() ?: LoversProfile()
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        
        val diff = now - current.lastInteractionTime
        val newStreak = if (diff in oneDayMs until (2 * oneDayMs)) {
            current.streakCount + 1
        } else if (diff >= (2 * oneDayMs)) {
            1
        } else {
            current.streakCount // same-day connection
        }

        val updated = current.copy(
            streakCount = newStreak,
            totalScore = current.totalScore + 1,
            lastInteractionTime = now
        )
        dao.updateProfile(updated)
    }

    // -------------------------------------------------------------
    // CHAT MESSAGES OPERATIONS
    // -------------------------------------------------------------
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
        msgEffect: String = ""
    ) {
        val msg = LoveMessage(
            senderName = sender,
            senderEmail = senderEmail,
            receiverEmail = receiverEmail,
            text = text,
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

    // -------------------------------------------------------------
    // SNAPCHAT SNAP OPERATIONS
    // -------------------------------------------------------------
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
        receiverEmail: String = ""
    ) {
        val exp = System.currentTimeMillis() + (durationSec * 1000L)
        val snap = CoupleSnap(
            senderName = sender,
            senderEmail = senderEmail,
            receiverEmail = receiverEmail,
            snapType = snapType,
            description = description,
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

    suspend fun viewSnapAndTriggerStreak(snapId: Int) {
        dao.markSnapAsViewed(snapId, System.currentTimeMillis())
        recordSnapStreakActivity()
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

    // -------------------------------------------------------------
    // SOCIAL STORIES / MOMENTS OPERATIONS
    // -------------------------------------------------------------
    val allStories: Flow<List<CoupleStory>> = dao.getAllStoriesFlow()

    fun getStoriesForCouple(myEmail: String, partnerEmail: String): Flow<List<CoupleStory>> {
        return dao.getCoupleStoriesFlow(myEmail, partnerEmail)
    }

    suspend fun shareStory(
        sender: String,
        description: String,
        imageType: String,
        senderEmail: String = ""
    ) {
        val story = CoupleStory(
            senderName = sender,
            senderEmail = senderEmail,
            description = description,
            imageType = imageType
        )
        dao.insertStory(story)
        incrementSnapScore(3)
        if (senderEmail.isNotEmpty()) {
            incrementUserScore(senderEmail, 3)
            // Increment admin analytics statistics
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

    // -------------------------------------------------------------
    // ADMIN ACTIONS
    // -------------------------------------------------------------
    suspend fun getAllUsers(): List<User> {
        return dao.getAllUsersDirect()
    }

    suspend fun deleteUserAccount(email: String) {
        dao.deleteUserByEmail(email)
    }
}
