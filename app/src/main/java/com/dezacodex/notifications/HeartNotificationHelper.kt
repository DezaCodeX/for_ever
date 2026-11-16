package com.dezacodex.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dezacodex.MainActivity
import com.dezacodex.R

/**
 * Manages NotificationChannels and local/FCM push notifications for:
 * 1. Private Messages
 * 2. Voice & Video Calls
 * 3. Couple Streak Milestones
 * 4. Snaps & Screenshot Alerts
 */
object HeartNotificationHelper {

    const val CHANNEL_MESSAGES = "heartsync_messages"
    const val CHANNEL_CALLS = "heartsync_calls"
    const val CHANNEL_STREAKS = "heartsync_streaks"
    const val CHANNEL_SNAPS = "heartsync_snaps"

    fun ensureChannelsCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channels = listOf(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Love Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant alerts for private messages and reactions"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_CALLS,
                "Voice & Video Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming and ongoing multi-stream voice and video calls"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_STREAKS,
                "Streak & Score Milestones",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders and celebrations for your couple streak"
            },
            NotificationChannel(
                CHANNEL_SNAPS,
                "Snaps & Screenshot Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New snaps, stories, and screenshot security alerts"
                enableVibration(true)
            }
        )
        manager.createNotificationChannels(channels)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showNotification(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    ): Boolean {
        ensureChannelsCreated(context)
        if (!hasNotificationPermission(context)) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priority = if (channelId == CHANNEL_CALLS || channelId == CHANNEL_MESSAGES || channelId == CHANNEL_SNAPS) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun channelForType(type: String): String = when (type.uppercase()) {
        "CALL" -> CHANNEL_CALLS
        "STREAK", "PAIRING" -> CHANNEL_STREAKS
        "SNAP", "SCREENSHOT", "STORY" -> CHANNEL_SNAPS
        else -> CHANNEL_MESSAGES
    }
}
