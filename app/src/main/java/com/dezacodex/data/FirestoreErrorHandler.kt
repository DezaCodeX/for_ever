package com.dezacodex.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject

enum class OperationType {
    CREATE,
    UPDATE,
    DELETE,
    LIST,
    GET,
    WRITE
}

data class FirestoreErrorInfo(
    val error: String,
    val operationType: OperationType,
    val path: String?,
    val userId: String?,
    val isEmailVerified: Boolean?
) {
    fun toJsonString(): String {
        return JSONObject().apply {
            put("error", error)
            put("operationType", operationType.name.lowercase())
            put("path", path ?: JSONObject.NULL)
            put("authInfo", JSONObject().apply {
                put("userId", userId ?: JSONObject.NULL)
                put("emailVerified", isEmailVerified ?: JSONObject.NULL)
            })
        }.toString()
    }
}

object FirestoreErrorHandler {
    private const val TAG = "FirestoreError"

    fun handleFirestoreError(
        exception: Throwable,
        operationType: OperationType,
        path: String?,
        auth: FirebaseAuth? = null
    ): FirestoreErrorInfo {
        val currentUser = auth?.currentUser
        val info = FirestoreErrorInfo(
            error = exception.message ?: exception.javaClass.simpleName,
            operationType = operationType,
            path = path,
            userId = currentUser?.uid,
            isEmailVerified = currentUser?.isEmailVerified
        )
        Log.e(TAG, "Firestore Error: ${info.toJsonString()}")
        return info
    }
}
