package com.dezacodex.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Checks GitHub releases for version updates and opens the official release page in browser.
 * Does not download or sideload APK binaries dynamically (Google Play policy compliant).
 */
class UpdateManager(private val context: Context) {

    private val repoOwner = "DezaCodex"
    private val repoName = "HeartSync-App"

    suspend fun checkForUpdate(currentVersion: String): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
                    .header("User-Agent", "HeartSync-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("UpdateManager", "GitHub release fetch failed with code ${response.code}")
                        return@withContext Pair(false, null)
                    }
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val latestVersion = json.getString("tag_name").replace("v", "").trim()
                    val cleanedCurrent = currentVersion.replace("v", "").trim()
                    val releaseHtmlUrl = json.optString("html_url")
                        .ifBlank { "https://github.com/$repoOwner/$repoName/releases/latest" }

                    val updateAvailable = cleanVersion(latestVersion) > cleanVersion(cleanedCurrent)
                    Pair(updateAvailable, releaseHtmlUrl)
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Error checking for updates", e)
                Pair(false, null)
            }
        }
    }

    private fun cleanVersion(version: String): Double {
        return try {
            val parts = version.split(".")
            if (parts.isNotEmpty()) {
                val major = parts[0].toDoubleOrNull() ?: 0.0
                val minor = if (parts.size > 1) parts[1].toDoubleOrNull() ?: 0.0 else 0.0
                val patch = if (parts.size > 2) parts[2].toDoubleOrNull() ?: 0.0 else 0.0
                major + (minor * 0.1) + (patch * 0.01)
            } else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    fun openReleasePage(releaseUrl: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("UpdateManager", "Unable to open release page", e)
            false
        }
    }
}
