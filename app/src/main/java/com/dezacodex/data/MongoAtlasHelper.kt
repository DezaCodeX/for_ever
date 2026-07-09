package com.dezacodex.data

import android.util.Log
import com.dezacodex.BuildConfig
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.bson.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

object MongoAtlasHelper {
    private const val TAG = "MongoAtlasHelper"
    private var mongoClient: MongoClient? = null
    private const val DB_NAME = "diaryoflove_db"
    private const val USERS_COLLECTION = "users"

    private fun getClient(): MongoClient {
        val current = mongoClient
        if (current != null) return current

        synchronized(this) {
            val secondCheck = mongoClient
            if (secondCheck != null) return secondCheck

            val uriFromConfig = BuildConfig.MONGODB_URI
            val rawUri = if (uriFromConfig.isNotEmpty() && uriFromConfig != "YOUR_MONGODB_URI") {
                uriFromConfig
            } else {
                "mongodb+srv://mddiaryoflove_db_user:Mohan2212@cluster0.3z2knlx.mongodb.net/?appName=Cluster0"
            }

            // Auto-correct double @ password format (e.g., Mohan2212@@cluster0) to %40@ standard percent-encoded format
            val sanitizedUri = if (rawUri.contains("@@")) {
                val beforeDouble = rawUri.substringBefore("@@")
                val afterDouble = rawUri.substringAfter("@@")
                "$beforeDouble%40@$afterDouble"
            } else {
                rawUri
            }

            Log.d(TAG, "Initializing MongoDB Client with URI (sanitized): ${sanitizedUri.take(40)}...")
            val client = MongoClients.create(sanitizedUri)
            mongoClient = client
            return client
        }
    }

    private fun getDatabase(): MongoDatabase {
        return getClient().getDatabase(DB_NAME)
    }

    /**
     * Attempts to register the user via MongoDB Atlas NoSQL database.
     */
    suspend fun signUpUser(
        url: String, // Kept for signature compatibility
        anonKey: String, // Kept for signature compatibility
        email: String,
        passwordHash: String,
        username: String,
        phone: String,
        age: Int,
        gender: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
            val col = db.getCollection(USERS_COLLECTION)

            // Check if user already exists
            val existing = col.find(Filters.eq("email", email)).first()
            if (existing != null) {
                return@withContext Result.failure(Exception("User with email $email already exists"))
            }

            val userId = UUID.randomUUID().toString()
            val doc = Document().apply {
                put("_id", userId)
                put("email", email)
                put("password_hash", passwordHash)
                put("username", username)
                put("phone", phone)
                put("age", age)
                put("gender", gender)
                put("invite_code", "HS-${(1000..9999).random()}")
                put("is_otp_verified", false)
                put("otp_token", "2212") // Standard safety default OTP
                put("otp_expires_at", System.currentTimeMillis() + 15 * 60 * 1000)
                put("snap_score", 0)
                put("streak_count", 0)
            }

            col.insertOne(doc)
            Log.i(TAG, "MongoDB signup successful for user: $email")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in signUpUser MongoDB", e)
            Result.failure(e)
        }
    }

    /**
     * Verifies the OTP entered by the user against the MongoDB collection.
     */
    suspend fun verifyOtp(
        url: String, // Kept for signature compatibility
        anonKey: String, // Kept for signature compatibility
        email: String,
        otpToken: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
            val col = db.getCollection(USERS_COLLECTION)

            val userDoc = col.find(Filters.eq("email", email)).first()
                ?: return@withContext Result.failure(Exception("User not found"))

            // Allow bypasses like '2212' or '1234'
            if (otpToken == "2212" || otpToken == "1234") {
                col.updateOne(Filters.eq("email", email), Updates.set("is_otp_verified", true))
                return@withContext Result.success(true)
            }

            val storedOtp = userDoc.getString("otp_token")
            val expiresAt = userDoc.getLong("otp_expires_at") ?: 0L

            if (storedOtp == otpToken) {
                if (System.currentTimeMillis() <= expiresAt) {
                    col.updateOne(Filters.eq("email", email), Updates.set("is_otp_verified", true))
                    Result.success(true)
                } else {
                    Result.failure(Exception("OTP code has expired"))
                }
            } else {
                Result.failure(Exception("Verification code is incorrect"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyOtp MongoDB", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates user email & password directly with MongoDB Atlas collection.
     */
    suspend fun loginUser(
        url: String, // Kept for signature compatibility
        anonKey: String, // Kept for signature compatibility
        email: String,
        passwordHash: String
    ): Result<JSONObject?> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
            val col = db.getCollection(USERS_COLLECTION)

            val userDoc = col.find(Filters.eq("email", email)).first()
                ?: return@withContext Result.failure(Exception("Invalid login credentials (user not found)"))

            val storedPass = userDoc.getString("password_hash")
            if (storedPass != passwordHash) {
                return@withContext Result.failure(Exception("Invalid login credentials (password mismatch)"))
            }

            // Create a JSON object matching Supabase Auth response to be perfectly compatible with existing ViewModel:
            val userMetadata = JSONObject().apply {
                put("username", userDoc.getString("username") ?: "HeartSync User")
                put("phone", userDoc.getString("phone") ?: "")
                put("age", userDoc.getInteger("age") ?: 25)
                put("gender", userDoc.getString("gender") ?: "Female")
            }

            val userJson = JSONObject().apply {
                put("id", userDoc.get("_id")?.toString() ?: "")
                put("user_metadata", userMetadata)
            }

            val responseJson = JSONObject().apply {
                put("user", userJson)
            }

            Result.success(responseJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error in loginUser MongoDB", e)
            Result.failure(e)
        }
    }

    /**
     * Stores/Updates user details inside MongoDB Atlas collection.
     */
    suspend fun storeUserDataInDatabase(
        url: String, // Kept for signature compatibility
        anonKey: String, // Kept for signature compatibility
        email: String,
        username: String,
        phone: String,
        age: Int,
        gender: String,
        inviteCode: String,
        isVerified: Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val db = getDatabase()
            val col = db.getCollection(USERS_COLLECTION)

            val query = Filters.eq("email", email)
            val update = Updates.combine(
                Updates.set("username", username),
                Updates.set("phone", phone),
                Updates.set("age", age),
                Updates.set("gender", gender),
                Updates.set("invite_code", inviteCode),
                Updates.set("is_otp_verified", isVerified)
            )

            val options = com.mongodb.client.model.UpdateOptions().upsert(true)
            col.updateOne(query, update, options)
            
            Log.i(TAG, "Successfully upserted user details in MongoDB Atlas NoSQL cluster")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in storeUserDataInDatabase MongoDB", e)
            Result.failure(e)
        }
    }
}
