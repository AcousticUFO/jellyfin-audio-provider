package com.github.jellyfin_saf.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.jellyfin_saf.R
import com.github.jellyfin_saf.ui.MainActivity

/**
 * Lightweight foreground service that maintains process priority and network connectivity
 * while audio tracks are actively streaming to an external media player (such as Poweramp).
 *
 * Prevents OS-level background execution limits (e.g. Samsung One UI App Freezer, Android Doze mode)
 * from freezing the process or cutting network sockets when the user switches apps or locks the screen.
 */
class StreamingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stopping streaming foreground service")
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        val notification = buildStreamingNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Streaming foreground service started successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to elevate to foreground service", e)
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "JellyfinAudioProvider:StreamingForegroundService"
            )?.apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 30-minute safety timeout
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire streaming service wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun buildStreamingNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.streaming_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.streaming_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "jellyfin_streaming_channel"
        private const val NOTIFICATION_ID = 1003
        const val ACTION_START = "com.github.jellyfin_saf.action.START_STREAMING"
        const val ACTION_STOP = "com.github.jellyfin_saf.action.STOP_STREAMING"

        fun start(context: Context) {
            try {
                val intent = Intent(context, StreamingService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start streaming service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, StreamingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop streaming service: ${e.message}")
            }
        }
    }
}
