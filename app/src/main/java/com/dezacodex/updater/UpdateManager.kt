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

                    val updateAvailable = isNewerVersion(latestVersion, cleanedCurrent)
                    Pair(updateAvailable, releaseHtmlUrl)
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Error checking for updates", e)
                Pair(false, null)
            }
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

    companion object {
        fun parseSemverParts(version: String): Triple<Int, Int, Int> {
            val normalized = version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .substringBefore("+")
                .trim()
            val parts = normalized.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return Triple(major, minor, patch)
        }

        fun isNewerVersion(candidateVersion: String, currentVersion: String): Boolean {
            val (candMaj, candMin, candPat) = parseSemverParts(candidateVersion)
            val (currMaj, currMin, currPat) = parseSemverParts(currentVersion)
            if (candMaj != currMaj) return candMaj > currMaj
            if (candMin != currMin) return candMin > currMin
            return candPat > currPat
        }
    }
}
