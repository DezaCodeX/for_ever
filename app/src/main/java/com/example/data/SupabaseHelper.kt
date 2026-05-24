package com.example.data

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SupabaseHelper {
    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val TAG = "SupabaseHelper"

    /**
     * Attempts to register the user via Supabase Auth.
     * This will automatically send a real verification signup OTP/email to the entered email address.
     */
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
        if (url.isEmpty() || anonKey.isEmpty() || url == "YOUR_SUPABASE_PROJECT_URL") {
            return@withContext Result.failure(Exception("Supabase is not configured"))
        }

        try {
            val signUpUrl = "${url.trimEnd('/')}/auth/v1/signup"

            val jsonBody = JSONObject().apply {
                put("email", email)
                put("password", passwordHash)
                if (phone.isNotEmpty()) {
                    put("phone", phone)
                }
                
                // Add metadata for user
                val metadata = JSONObject().apply {
                    put("username", username)
                    put("phone", phone)
                    put("age", age)
                    put("gender", gender)
                    put("invite_code", "HS-${(1000..9999).random()}")
                    put("is_otp_verified", false)
                }
                put("data", metadata)
                put("user_metadata", metadata)
            }

            val request = Request.Builder()
                .url(signUpUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d(TAG, "SignUp response (${response.code}): $bodyStr")
                
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(bodyStr)
                    val userId = jsonResponse.optJSONObject("user")?.optString("id")
                    // Real activation email/OTP was triggered successfully by Supabase Auth!
                    Result.success(userId)
                } else {
                    var errorMsg = ""
                    try {
                        val jsonObj = JSONObject(bodyStr)
                        errorMsg = jsonObj.optString("error_description", "")
                        if (errorMsg.isEmpty()) errorMsg = jsonObj.optString("message", "")
                        if (errorMsg.isEmpty()) errorMsg = jsonObj.optString("msg", "")
                        if (errorMsg.isEmpty()) errorMsg = jsonObj.optString("error", "")
                    } catch (e: Exception) {
                        // Ignore and use fallback
                    }
                    if (errorMsg.isEmpty()) {
                        errorMsg = if (response.message.isNotEmpty()) {
                            "Signup failed: ${response.message}"
                        } else {
                            "Signup failed (HTTP ${response.code})"
                        }
                        if (bodyStr.isNotEmpty()) {
                            errorMsg += " - $bodyStr"
                        }
                    }
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in signUpUser", e)
            Result.failure(e)
        }
    }

    /**
     * Verifies the OTP entered by the user against Supabase Auth.
     */
    suspend fun verifyOtp(
        url: String,
        anonKey: String,
        email: String,
        otpToken: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty() || url == "YOUR_SUPABASE_PROJECT_URL") {
            return@withContext Result.failure(Exception("Supabase is not configured"))
        }

        try {
            val verifyUrl = "${url.trimEnd('/')}/auth/v1/verify"

            val jsonBody = JSONObject().apply {
                put("type", "signup")
                put("email", email)
                put("token", otpToken)
            }

            val request = Request.Builder()
                .url(verifyUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d(TAG, "VerifyOTP response (${response.code}): $bodyStr")

                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    // Try type: email in case type signup fails (depending on Supabase configuration)
                    val retryBody = JSONObject().apply {
                        put("type", "email")
                        put("email", email)
                        put("token", otpToken)
                    }
                    val retryRequest = Request.Builder()
                        .url(verifyUrl)
                        .addHeader("apikey", anonKey)
                        .addHeader("Content-Type", "application/json")
                        .post(retryBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    client.newCall(retryRequest).execute().use { retryResp ->
                        val retryBodyStr = retryResp.body?.string() ?: ""
                        Log.d(TAG, "VerifyOTP retry response (${retryResp.code}): $retryBodyStr")
                        if (retryResp.isSuccessful) {
                            Result.success(true)
                        } else {
                            var errorMsg3 = ""
                            try {
                                val jsonObj = JSONObject(retryBodyStr)
                                errorMsg3 = jsonObj.optString("error_description", "")
                                if (errorMsg3.isEmpty()) errorMsg3 = jsonObj.optString("message", "")
                                if (errorMsg3.isEmpty()) errorMsg3 = jsonObj.optString("msg", "")
                                if (errorMsg3.isEmpty()) errorMsg3 = jsonObj.optString("error", "")
                            } catch (e: java.lang.Exception) {
                                // Ignore
                            }
                            if (errorMsg3.isEmpty()) {
                                errorMsg3 = "Verification code is incorrect"
                            }
                            Result.failure(Exception(errorMsg3))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyOtp", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates user email & password directly with Supabase Auth.
     */
    suspend fun loginUser(
        url: String,
        anonKey: String,
        email: String,
        passwordHash: String
    ): Result<JSONObject?> = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty() || url == "YOUR_SUPABASE_PROJECT_URL") {
            return@withContext Result.failure(Exception("Supabase is not configured"))
        }

        try {
            val loginUrl = "${url.trimEnd('/')}/auth/v1/token?grant_type=password"

            val jsonBody = JSONObject().apply {
                put("email", email)
                put("password", passwordHash)
            }

            val request = Request.Builder()
                .url(loginUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.d(TAG, "Login response (${response.code}): $bodyStr")

                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(bodyStr)
                    Result.success(jsonResponse)
                } else {
                    var errorMsg2 = ""
                    try {
                        val jsonObj = JSONObject(bodyStr)
                        errorMsg2 = jsonObj.optString("error_description", "")
                        if (errorMsg2.isEmpty()) errorMsg2 = jsonObj.optString("message", "")
                        if (errorMsg2.isEmpty()) errorMsg2 = jsonObj.optString("msg", "")
                        if (errorMsg2.isEmpty()) errorMsg2 = jsonObj.optString("error", "")
                    } catch (e: java.lang.Exception) {
                        // Ignore
                    }
                    if (errorMsg2.isEmpty()) {
                        errorMsg2 = "Invalid login credentials"
                    }
                    Result.failure(Exception(errorMsg2))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loginUser", e)
            Result.failure(e)
        }
    }

    /**
     * Stores user details inside Supabase Database (e.g., users table, profiles table, and user_profiles table).
     * Attempts insertions in multiple likely table targets to be 100% robust against developer schema design.
     */
    suspend fun storeUserDataInDatabase(
        url: String,
        anonKey: String,
        email: String,
        username: String,
        phone: String,
        age: Int,
        gender: String,
        inviteCode: String,
        isVerified: Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty() || url == "YOUR_SUPABASE_PROJECT_URL") {
            return@withContext Result.failure(Exception("Supabase is not configured"))
        }

        var isAnySuccess = false
        val errors = mutableListOf<String>()

        val userJson = JSONObject().apply {
            put("email", email)
            put("username", username)
            put("phone", phone)
            put("age", age)
            put("gender", gender)
            put("invite_code", inviteCode)
            put("is_otp_verified", isVerified)
            put("snap_score", 0)
            put("streak_count", 0)
        }

        // We try posting to several tables to maximize chances of finding the matching database design.
        val targetTables = listOf("users", "heart_users", "users_table", "profiles")

        for (table in targetTables) {
            try {
                val dbUrl = "${url.trimEnd('/')}/rest/v1/$table"
                val request = Request.Builder()
                    .url(dbUrl)
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $anonKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "resolution=merge-duplicates") // Merge duplicates for upsert upsert-compatibility
                    .post(userJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    Log.d(TAG, "Store in table '$table' returned (${response.code}): $bodyStr")
                    if (response.isSuccessful || response.code == 201) {
                        isAnySuccess = true
                        Log.i(TAG, "Successfully saved user data in Supabase table '$table'")
                    } else {
                        errors.add("$table: code ${response.code} ($bodyStr)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed storing details in table '$table'", e)
                errors.add("$table: Exception ${e.localizedMessage}")
            }
        }

        if (isAnySuccess) {
            Result.success(true)
        } else {
            Result.failure(Exception("Could not store database row in table. Trace: ${errors.joinToString()}"))
        }
    }
}
