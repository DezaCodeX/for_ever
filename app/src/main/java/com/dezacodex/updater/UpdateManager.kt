package com.dezacodex.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.util.Log

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

                    // Check if there are assets
                    val assets = json.optJSONArray("assets")
                    if (assets == null || assets.length() == 0) {
                        return@withContext Pair(false, null)
                    }
                    val apkUrl = assets.getJSONObject(0).getString("browser_download_url")

                    // Semantic or standard numeric comparison
                    val latestDouble = cleanVersion(latestVersion)
                    val currentDouble = cleanVersion(cleanedCurrent)
                    val updateAvailable = latestDouble > currentDouble
                    Log.i("UpdateManager", "Latest release: $latestVersion ($latestDouble), Current version: $cleanedCurrent ($currentDouble), Update available: $updateAvailable")
                    Pair(updateAvailable, apkUrl)
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
            } else {
                0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    suspend fun downloadAndInstall(apkUrl: String, onProgress: (Float) -> Unit = {}): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(apkUrl)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    val totalBytes = body.contentLength()
                    val apkFile = File(context.cacheDir, "heartsync_update.apk")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }
                    
                    body.byteStream().use { inputStream ->
                        FileOutputStream(apkFile).use { outputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalDownloaded = 0L
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalDownloaded += bytesRead
                                if (totalBytes > 0) {
                                    onProgress(totalDownloaded.toFloat() / totalBytes)
                                }
                              }
                        }
                    }
                    installApk(apkFile)
                    true
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Error downloading APK", e)
                false
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
