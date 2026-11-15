package com.dezacodex.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed class StorageUploadResult {
    data class Success(val downloadUrl: String, val storagePath: String) : StorageUploadResult()
    data class RequiresConfiguration(val reason: String) : StorageUploadResult()
    data class Error(val message: String) : StorageUploadResult()
}

/**
 * Handles media uploads to Firebase Storage buckets (avatars, snaps, stories, chat images)
 * and real local device downloads via MediaStore / app storage without fake paths.
 */
class FirebaseStorageRepository(
    private val context: Context,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    private val httpClient = OkHttpClient()

    suspend fun uploadImageUri(
        bucketFolder: String,
        ownerUid: String,
        localUri: Uri
    ): StorageUploadResult = withContext(Dispatchers.IO) {
        val safeFolder = bucketFolder.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val filename = "${UUID.randomUUID()}.jpg"
        val path = "$safeFolder/$ownerUid/$filename"
        try {
            val bytes = context.contentResolver.openInputStream(localUri)?.use { it.readBytes() }
                ?: return@withContext StorageUploadResult.Error("Unable to read selected image.")
            uploadBytes(path, bytes)
        } catch (e: Exception) {
            mapStorageError(e)
        }
    }

    suspend fun uploadBytes(
        storagePath: String,
        bytes: ByteArray
    ): StorageUploadResult = withContext(Dispatchers.IO) {
        try {
            val ref = storage.reference.child(storagePath)
            ref.putBytes(bytes).await()
            val url = ref.downloadUrl.await().toString()
            StorageUploadResult.Success(downloadUrl = url, storagePath = storagePath)
        } catch (e: Exception) {
            mapStorageError(e)
        }
    }

    private fun mapStorageError(e: Exception): StorageUploadResult {
        Log.e(TAG, "Storage operation failed: ${e.message}")
        if (e is StorageException && (
                e.errorCode == StorageException.ERROR_BUCKET_NOT_FOUND ||
                e.errorCode == StorageException.ERROR_PROJECT_NOT_FOUND ||
                e.errorCode == StorageException.ERROR_NOT_AUTHORIZED
            )
        ) {
            return StorageUploadResult.RequiresConfiguration(
                "Firebase Storage bucket or rules need initialization in Firebase Console (${e.errorCode})."
            )
        }
        return StorageUploadResult.Error(e.localizedMessage ?: "Storage upload failed.")
    }

    /**
     * Writes real bytes (from HTTPS URL or renders a crisp fallback text card file) into Downloads/MediaStore.
     */
    suspend fun saveMediaToDeviceDownloads(
        filename: String,
        mediaUrl: String?,
        fallbackTextSummary: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val payloadBytes: ByteArray = if (!mediaUrl.isNullOrBlank() && mediaUrl.startsWith("http")) {
                val req = Request.Builder().url(mediaUrl).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.bytes() ?: fallbackTextSummary.toByteArray(Charsets.UTF_8)
                    } else {
                        fallbackTextSummary.toByteArray(Charsets.UTF_8)
                    }
                }
            } else {
                fallbackTextSummary.toByteArray(Charsets.UTF_8)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, if (safeName.endsWith(".jpg")) "image/jpeg" else "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/HeartSync")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payloadBytes)
                    }
                    return@withContext Result.success(uri.toString())
                }
            }

            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "HeartSync")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, safeName)
            FileOutputStream(outFile).use { it.write(payloadBytes) }
            Result.success(outFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Download to local storage failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "FirebaseStorageRepo"
    }
}
