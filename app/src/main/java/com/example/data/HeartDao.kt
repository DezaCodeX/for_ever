package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartDao {
    // 1. User operations
    @Query("SELECT * FROM users_table WHERE email = :email LIMIT 1")
    fun getUserByEmailFlow(email: String): Flow<User?>

    @Query("SELECT * FROM users_table WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users_table WHERE phone = :phone LIMIT 1")
    suspend fun getUserByPhone(phone: String): User?

    @Query("SELECT * FROM users_table WHERE inviteCode = :code LIMIT 1")
    suspend fun getUserByInviteCode(code: String): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Query("SELECT * FROM users_table")
    fun getAllUsersFlow(): Flow<List<User>>

    @Query("SELECT * FROM users_table")
    suspend fun getAllUsersDirect(): List<User>

    @Query("DELETE FROM users_table WHERE email = :email")
    suspend fun deleteUserByEmail(email: String)

    // 2. Profile DAOs (legacy compatibility support)
    @Query("SELECT * FROM lovers_profiles WHERE id = 1 LIMIT 1")
    fun getProfileFlow(): Flow<LoversProfile?>

    @Query("SELECT * FROM lovers_profiles WHERE id = 1 LIMIT 1")
    suspend fun getProfileDirect(): LoversProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateProfile(profile: LoversProfile)

    // 3. Private Chat DAOs
    @Query("SELECT * FROM love_messages ORDER BY timestamp ASC")
    fun getAllMessagesFlow(): Flow<List<LoveMessage>>

    @Query("SELECT * FROM love_messages WHERE (senderEmail = :myEmail AND receiverEmail = :pEmail) OR (senderEmail = :pEmail AND receiverEmail = :myEmail) ORDER BY timestamp ASC")
    fun getCoupleMessagesFlow(myEmail: String, pEmail: String): Flow<List<LoveMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LoveMessage)

    @Query("UPDATE love_messages SET reaction = :reaction WHERE id = :id")
    suspend fun updateMessageReaction(id: Int, reaction: String)

    @Query("UPDATE love_messages SET isSavedInChat = :saved WHERE id = :id")
    suspend fun updateMessageSaved(id: Int, saved: Boolean)

    @Query("DELETE FROM love_messages WHERE id = :id")
    suspend fun deleteMessageById(id: Int)

    @Query("DELETE FROM love_messages")
    suspend fun clearAllMessages()

    // 4. Snapchat Snaps DAOs
    @Query("SELECT * FROM couple_snaps ORDER BY timestamp DESC")
    fun getAllSnapsFlow(): Flow<List<CoupleSnap>>

    @Query("SELECT * FROM couple_snaps WHERE (senderEmail = :myEmail AND receiverEmail = :pEmail) OR (senderEmail = :pEmail AND receiverEmail = :myEmail) ORDER BY timestamp DESC")
    fun getCoupleSnapsFlow(myEmail: String, pEmail: String): Flow<List<CoupleSnap>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnap(snap: CoupleSnap)

    @Query("UPDATE couple_snaps SET viewed = 1, viewedTimestamp = :viewedTime WHERE id = :id")
    suspend fun markSnapAsViewed(id: Int, viewedTime: Long)

    @Query("UPDATE couple_snaps SET screenshotAlert = 1 WHERE id = :id")
    suspend fun alertScreenshot(id: Int)

    @Query("UPDATE couple_snaps SET isSavedInChat = :saved WHERE id = :id")
    suspend fun updateSnapSaved(id: Int, saved: Boolean)

    @Query("UPDATE couple_snaps SET savedInLocalStoragePath = :path WHERE id = :id")
    suspend fun updateSnapLocalStorage(id: Int, path: String)

    @Query("DELETE FROM couple_snaps WHERE id = :id")
    suspend fun deleteSnapById(id: Int)

    // 5. Stories / Moments DAOs
    @Query("SELECT * FROM couple_stories ORDER BY timestamp DESC")
    fun getAllStoriesFlow(): Flow<List<CoupleStory>>

    @Query("SELECT * FROM couple_stories WHERE senderEmail = :myEmail OR senderEmail = :partnerEmail ORDER BY timestamp DESC")
    fun getCoupleStoriesFlow(myEmail: String, partnerEmail: String): Flow<List<CoupleStory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStory(story: CoupleStory)

    @Query("UPDATE couple_stories SET isSavedInChat = :saved WHERE id = :id")
    suspend fun updateStorySaved(id: Int, saved: Boolean)

    @Query("UPDATE couple_stories SET savedInLocalStoragePath = :path WHERE id = :id")
    suspend fun updateStoryLocalStorage(id: Int, path: String)

    @Query("DELETE FROM couple_stories WHERE id = :id")
    suspend fun deleteStoryById(id: Int)
}
