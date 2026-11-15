package com.dezacodex.notifications

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.dezacodex.R

/**
 * Handles Firebase Cloud Messaging token refreshes and incoming push payloads
 * for messages, voice/video calls, streaks, and snap screenshot alerts.
 */
class HeartSyncMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        HeartNotificationHelper.ensureChannelsCreated(applicationContext)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val dbId = getString(R.string.firestore_database_id)
        val firestore = FirebaseFirestore.getInstance(dbId)
        firestore.collection("users").document(user.uid)
            .update(
                mapOf(
                    "fcmToken" to token.take(512),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed updating refreshed FCM token: ${e.message}")
            }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val dataType = message.data["type"] ?: "MESSAGE"
        val channelId = HeartNotificationHelper.channelForType(dataType)
        val title = message.notification?.title ?: message.data["title"] ?: "HeartSync"
        val body = message.notification?.body ?: message.data["body"] ?: "New activity from your partner"
        HeartNotificationHelper.showNotification(
            context = applicationContext,
            channelId = channelId,
            title = title,
            body = body
        )
    }

    companion object {
        private const val TAG = "HeartSyncMessagingSvc"
    }
}
