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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.github.jellyfin_saf.R
import com.github.jellyfin_saf.ui.MainActivity

/**
 * Persistent foreground service with an active [MediaSessionCompat] that signals
 * to Android's OomAdjuster and Samsung One UI App Freezer (MARs) that this process
 * is actively involved in audio playback and must not be frozen or killed.
 *
 * Without a MediaSession in STATE_PLAYING, even a foreground service with
 * FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK can be cgroup-frozen during app transitions,
 * causing micro-cuts and total audio loss in the external player (Poweramp) that reads
 * audio data from our SAF DocumentsProvider via FUSE/Binder IPC.
 *
 * The MediaSession does not control playback (Poweramp does). It exists solely as the
 * industry-standard process-priority signal that all music apps use to prevent OS-level
 * background execution limits.
 */
class StreamingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()

        val notification = buildMediaStyleNotification()

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
            Log.d(TAG, "Streaming foreground service started with active MediaSession")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to elevate to foreground service", e)
        }

        return START_STICKY
    }

    /**
     * Initializes the MediaSession with STATE_PLAYING playback state.
     *
     * This is the critical signal that Android's OomAdjuster checks to determine
     * whether a process is "important for audio." Samsung MARs and the cgroup freezer
     * exempt processes with an active MediaSession from suspension.
     */
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {

            // Set metadata identifying this as an audio streaming source
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.streaming_notification_title))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.streaming_notification_text))
                    .build()
            )

            // Set playback state to PLAYING — this is the key signal
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1.0f
                    )
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                    .build()
            )

            // Minimal callback — we don't actually control playback
            setCallback(object : MediaSessionCompat.Callback() {})

            isActive = true
        }
        Log.d(TAG, "MediaSession initialized and set to STATE_PLAYING")
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
                acquire() // Held indefinitely while the foreground service is running
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

    /**
     * Builds a MediaStyle notification linked to the active MediaSession.
     *
     * MediaStyle notifications with a session token are recognized by the Android system
     * as belonging to a legitimate media app. This grants additional process-priority
     * protection and enables lock-screen / notification shade media controls.
     */
    private fun buildMediaStyleNotification(): Notification {
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
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
            )
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
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        releaseWakeLock()
        Log.d(TAG, "StreamingService destroyed, MediaSession released")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StreamingService"
        private const val MEDIA_SESSION_TAG = "JellyfinAudioProvider"
        private const val CHANNEL_ID = "jellyfin_streaming_channel"
        private const val NOTIFICATION_ID = 1003
        const val ACTION_START = "com.github.jellyfin_saf.action.START_STREAMING"

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
    }
}
