package com.dezacodex.call

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dezacodex.R
import com.dezacodex.notifications.HeartNotificationHelper

/**
 * Foreground service required by Android 10+ / Android 14+ to sustain live WebRTC
 * MediaProjection screen sharing, camera capture, and microphone capture.
 */
class CallMediaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HeartNotificationHelper.ensureChannelsCreated(applicationContext)
        val hasScreenShare = intent?.getBooleanExtra(EXTRA_SCREEN_SHARE, false) ?: false
        val notification = buildOngoingCallNotification(hasScreenShare)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (hasScreenShare) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildOngoingCallNotification(hasScreenShare: Boolean): Notification {
        val text = if (hasScreenShare) {
            "Multi-stream video & screen sharing active"
        } else {
            "Voice & video call connected"
        }
        return NotificationCompat.Builder(this, HeartNotificationHelper.CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("HeartSync Live Call")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 7701
        private const val EXTRA_SCREEN_SHARE = "extra_screen_share"

        fun start(context: Context, withScreenShare: Boolean) {
            val intent = Intent(context, CallMediaService::class.java).apply {
                putExtra(EXTRA_SCREEN_SHARE, withScreenShare)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallMediaService::class.java))
        }
    }
}
