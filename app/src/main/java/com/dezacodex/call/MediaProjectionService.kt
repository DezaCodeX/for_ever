package com.dezacodex.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dezacodex.R

/**
 * Foreground Service required by Android 10–16 for active MediaProjection screen sharing
 * and ongoing camera/microphone call sessions.
 */
class MediaProjectionService : Service() {

    companion object {
        const val CHANNEL_ID = "heartsync_call_media_channel"
        const val NOTIFICATION_ID = 4102
        const val ACTION_START_CALL = "com.dezacodex.call.ACTION_START_CALL"
        const val ACTION_START_SCREEN_SHARE = "com.dezacodex.call.ACTION_START_SCREEN_SHARE"
        const val ACTION_STOP = "com.dezacodex.call.ACTION_STOP"

        fun startCallForeground(context: Context, includeScreenProjection: Boolean) {
            val intent = Intent(context, MediaProjectionService::class.java).apply {
                action = if (includeScreenProjection) ACTION_START_SCREEN_SHARE else ACTION_START_CALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopForeground(context: Context) {
            val intent = Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
            runCatching { context.stopService(Intent(context, MediaProjectionService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SCREEN_SHARE -> {
                startWithType(includeMediaProjection = true)
            }
            else -> {
                startWithType(includeMediaProjection = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun startWithType(includeMediaProjection: Boolean) {
        ensureNotificationChannel()
        val notification = buildNotification(includeMediaProjection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            if (fgsType != 0) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Video Call & Screen Share",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active audio, camera, and screen sharing call session"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(screenSharing: Boolean): Notification {
        val title = if (screenSharing) "for∞ever♥︎ Screen Share Active" else "for∞ever♥︎ Call Active"
        val body = if (screenSharing) {
            "Your screen and call audio are actively streaming."
        } else {
            "Encrypted audio & video call in progress."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
