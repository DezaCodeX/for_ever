package com.dezacodex.data

import android.util.Log
import com.dezacodex.security.SecurityUtils
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import org.json.JSONObject
import java.util.UUID

/**
 * Legacy optional MongoDB Atlas helper retained only when MONGODB_URI is explicitly provided
 * via runtime environment variables. Primary backend is Firebase Auth, Cloud Firestore & Storage.
 * Zero hardcoded credentials, URIs, or OTP bypass PINs remain.
 */
@Deprecated("Primary cloud backend migrated to Firebase Firestore, Auth & Storage.")
object MongoAtlasHelper {
    private const val TAG = "MongoAtlasHelper"
    private var mongoClient: MongoClient? = null
    private const val DB_NAME = "diaryoflove_db"
    private const val USERS_COLLECTION = "users"

    private fun getClient(): MongoClient? {
        val current = mongoClient
        if (current != null) return current

        synchronized(this) {
            mongoClient?.let { return it }
            val rawUri = System.getenv("MONGODB_URI")?.trim().orEmpty()
            if (rawUri.isEmpty()) {
                return null
            }
            val client = MongoClients.create(rawUri)
            mongoClient = client
            return client
        }
    }

    private fun getDatabase(): MongoDatabase? = getClient()?.getDatabase(DB_NAME)

    suspend fun signUpUser(
        url: String,
        anonKey: String,
        email: String,
        passwordHash: String,
        username: String,
        phone: String,
        age: Int,
        gender: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
                ?: return@withContext Result.failure(IllegalStateException("MongoDB URI is not configured; using Firebase."))
            val col = db.getCollection(USERS_COLLECTION)
            val existing = col.find(Filters.eq("email", email)).first()
            if (existing != null) {
                return@withContext Result.failure(Exception("User already exists"))
            }

            val userId = UUID.randomUUID().toString()
            val otp = SecurityUtils.generateSixDigitCode()
            val hashed = if (passwordHash.contains(":")) passwordHash else SecurityUtils.hashPassword(passwordHash)
            val doc = Document().apply {
                put("_id", userId)
                put("email", email)
                put("password_hash", hashed)
                put("username", username)
                put("phone", phone)
                put("age", age)
                put("gender", gender)
                put("invite_code", SecurityUtils.generateInviteCode())
                put("is_otp_verified", false)
                put("otp_token", otp)
                put("otp_expires_at", System.currentTimeMillis() + 5 * 60 * 1000)
                put("snap_score", 0)
                put("streak_count", 0)
            }
            col.insertOne(doc)
            Log.i(TAG, "MongoDB signup registered for userId=$userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in signUpUser MongoDB: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun verifyOtp(
        url: String,
        anonKey: String,
        email: String,
        otpToken: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
                ?: return@withContext Result.failure(IllegalStateException("MongoDB URI is not configured"))
            val col = db.getCollection(USERS_COLLECTION)
            val userDoc = col.find(Filters.eq("email", email)).first()
                ?: return@withContext Result.failure(Exception("User not found"))

            val storedOtp = userDoc.getString("otp_token")
            val expiresAt = userDoc.getLong("otp_expires_at") ?: 0L

            if (storedOtp != null && storedOtp == otpToken && System.currentTimeMillis() <= expiresAt) {
                col.updateOne(Filters.eq("email", email), Updates.set("is_otp_verified", true))
                Result.success(true)
            } else {
                Result.failure(Exception("Verification code is invalid or expired"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyOtp MongoDB: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun loginUser(
        url: String,
        anonKey: String,
        email: String,
        passwordRaw: String
    ): Result<JSONObject?> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
                ?: return@withContext Result.failure(IllegalStateException("MongoDB URI is not configured"))
            val col = db.getCollection(USERS_COLLECTION)
            val userDoc = col.find(Filters.eq("email", email)).first()
                ?: return@withContext Result.failure(Exception("Invalid login credentials"))

            val storedPass = userDoc.getString("password_hash") ?: ""
            if (!SecurityUtils.verifyPassword(passwordRaw, storedPass)) {
                return@withContext Result.failure(Exception("Invalid login credentials"))
            }

            val userMetadata = JSONObject().apply {
                put("username", userDoc.getString("username") ?: "HeartSync User")
                put("phone", userDoc.getString("phone") ?: "")
                put("age", userDoc.getInteger("age") ?: 25)
                put("gender", userDoc.getString("gender") ?: "")
            }
            val userJson = JSONObject().apply {
                put("id", userDoc.get("_id")?.toString() ?: "")
                put("user_metadata", userMetadata)
            }
            Result.success(JSONObject().apply { put("user", userJson) })
        } catch (e: Exception) {
            Log.e(TAG, "Error in loginUser MongoDB: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}
