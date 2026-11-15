package com.dezacodex.security

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed class GoogleAuthResult {
    data class Success(val user: FirebaseUser) : GoogleAuthResult()
    data class Error(val message: String, val isCancellation: Boolean = false) : GoogleAuthResult()
}

/**
 * Manages real Firebase Authentication with Google Sign-In via Jetpack Credential Manager.
 * Never uses anonymous sign-in or hardcoded OAuth client IDs.
 */
class GoogleAuthManager(
    private val context: Context,
    private val auth: FirebaseAuth
) {
    private val credentialManager = CredentialManager.create(context)

    fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private fun resolveWebClientId(): String {
        val resId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (resId == 0) return ""
        return context.getString(resId)
    }

    /**
     * Explicit button-triggered Google Sign-In using GetSignInWithGoogleOption.
     */
    suspend fun signInWithGoogle(activityContext: Context): GoogleAuthResult {
        val clientId = resolveWebClientId()
        if (clientId.isBlank()) {
            return GoogleAuthResult.Error(
                "Google Sign-In client ID is missing. Check google-services.json configuration."
            )
        }

        return try {
            val googleOption = GetSignInWithGoogleOption.Builder(clientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = activityContext
            )
            val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
            val authResult = auth.signInWithCredential(firebaseCred).await()
            val user = authResult.user
            if (user != null) {
                GoogleAuthResult.Success(user)
            } else {
                GoogleAuthResult.Error("Firebase authentication returned no user.")
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleAuthResult.Error("Google Sign-In canceled.", isCancellation = true)
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credentials available: ${e.message}")
            GoogleAuthResult.Error("No Google account found on this device. Add a Google account in device settings.")
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: ${e.type}")
            GoogleAuthResult.Error("Google Sign-In failed (${e.errorMessage ?: "credential error"}). Ensure SHA-1 is configured and retry.")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Google Sign-In failed: ${e.javaClass.simpleName}")
            GoogleAuthResult.Error(e.localizedMessage ?: "Sign-in failed. Please try again.")
        }
    }

    /**
     * Optional returning-user silent auto sign-in using GetGoogleIdOption.
     */
    suspend fun attemptSilentSignIn(activityContext: Activity): GoogleAuthResult {
        val clientId = resolveWebClientId()
        if (clientId.isBlank()) {
            return GoogleAuthResult.Error("Missing web client ID")
        }
        return try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(clientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(
                request = request,
                context = activityContext
            )
            val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
            val authResult = auth.signInWithCredential(firebaseCred).await()
            val user = authResult.user
            if (user != null) GoogleAuthResult.Success(user)
            else GoogleAuthResult.Error("No user returned")
        } catch (e: Exception) {
            GoogleAuthResult.Error(e.localizedMessage ?: "Silent sign-in unavailable", isCancellation = true)
        }
    }

    suspend fun signOut() {
        auth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed clearing credential state: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
    }
}
