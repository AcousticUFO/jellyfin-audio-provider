package com.github.jellyfin_saf.sync

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
import com.github.jellyfin_saf.api.JellyfinClient
import com.github.jellyfin_saf.cache.LRUCacheManager
import com.github.jellyfin_saf.db.AppDatabase
import com.github.jellyfin_saf.db.TrackEntity
import com.github.jellyfin_saf.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that performs a full library metadata sync in the background.
 *
 * This service survives activity destruction, home-button presses, and app switches.
 * It holds a partial wake lock during the sync to prevent the CPU from sleeping,
 * and posts a persistent notification with real-time progress updates.
 */
class LibrarySyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (LibrarySyncManager.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring duplicate start request")
            return START_NOT_STICKY
        }

        acquireWakeLock()
        startForeground(buildProgressNotification(0, 0))
        scope.launch { performSync() }
        return START_NOT_STICKY
    }

    /**
     * Executes the paginated metadata download, inserts into Room, and
     * updates both the notification and [LibrarySyncManager] state at each batch.
     */
    private suspend fun performSync() {
        try {
            val client = JellyfinClient(applicationContext)
            val db = AppDatabase.getInstance(applicationContext)

            val totalCount = client.getTotalAudioCount()
            LibrarySyncManager.updateState(
                LibrarySyncManager.SyncState.Syncing(0, totalCount, "Starting sync...")
            )
            updateNotification(0, totalCount)

            val libraryRoots = client.getLibraryRoots()
            Log.i(TAG, "Fetched ${libraryRoots.size} library roots: $libraryRoots")

            val syncedTrackIds = HashSet<String>(if (totalCount > 0) totalCount else 20000)
            var startIndex = 0
            var totalSynced = 0

            while (true) {
                val batch = client.getAllAudioTracks(limit = BATCH_SIZE, startIndex = startIndex)
                if (batch.isEmpty()) break

                val entities = batch.map { track ->
                    syncedTrackIds.add(track.id)
                    val (relDir, fileName) = track.calculateRelativePath(libraryRoots)
                    TrackEntity(
                        id = track.id,
                        albumId = track.resolvedAlbumId,
                        title = track.name ?: "Unknown Track",
                        artist = track.artistName,
                        album = track.albumName,
                        albumArtist = track.resolvedAlbumArtist,
                        composer = track.composerName,
                        genre = track.genres
                            ?.filter { g -> g.isNotBlank() }
                            ?.joinToString(", ")
                            ?.takeIf { g -> g.isNotBlank() },
                        durationMs = track.durationMs,
                        sizeBytes = track.mediaSize,
                        mimeType = track.mimeType,
                        year = track.resolvedYear,
                        trackNumber = track.resolvedTrackNumber,
                        discNumber = track.resolvedDiscNumber,
                        isFavorite = track.userData?.isFavorite ?: false,
                        path = track.path ?: track.mediaSources?.firstOrNull()?.path,
                        relativeDir = relDir,
                        fileName = fileName
                    )
                }

                db.trackDao().insertAll(entities)
                totalSynced += entities.size
                startIndex += batch.size

                LibrarySyncManager.updateState(
                    LibrarySyncManager.SyncState.Syncing(totalSynced, totalCount, "Downloading metadata...")
                )
                updateNotification(totalSynced, totalCount)

                if (batch.size < BATCH_SIZE) break
            }

            // Reconcile and prune obsolete tracks (deleted, renamed, or retagged on Jellyfin)
            val existingTrackIds = db.trackDao().getAllTrackIds().toSet()
            val obsoleteTrackIds = existingTrackIds - syncedTrackIds
            if (obsoleteTrackIds.isNotEmpty()) {
                Log.i(TAG, "Reconciling library: purging ${obsoleteTrackIds.size} obsolete tracks from local database")
                val cacheManager = LRUCacheManager(applicationContext)
                obsoleteTrackIds.chunked(500).forEach { chunk ->
                    db.trackDao().deleteTracksByIds(chunk)
                    for (id in chunk) {
                        cacheManager.evictTrack(id)
                    }
                }
            }

            val finalTrackCount = db.trackDao().getTotalTrackCount()
            val albumCount = db.trackDao().getAllAlbumSummaries().size

            // Invalidate provider directory cache so new structure is reflected immediately
            com.github.jellyfin_saf.provider.JellyfinDocumentsProvider.invalidateCache()

            LibrarySyncManager.updateState(
                LibrarySyncManager.SyncState.Completed(finalTrackCount, albumCount)
            )
            updateNotification(finalTrackCount, finalTrackCount, completed = true, albumCount = albumCount)

        } catch (e: Exception) {
            Log.e(TAG, "Library sync failed", e)
            LibrarySyncManager.updateState(
                LibrarySyncManager.SyncState.Error(e.message ?: "Unknown sync error")
            )
        } finally {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // -- Wake lock management --

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "JellyfinAudioProvider:LibrarySyncWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 30-minute safety ceiling
            }
            Log.d(TAG, "Wake lock acquired for library sync")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released after library sync")
            }
        }
        wakeLock = null
    }

    // -- Notification helpers --

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(
        current: Int,
        total: Int,
        completed: Boolean = false,
        albumCount: Int = 0
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        if (completed) {
            builder
                .setContentTitle("Library sync complete")
                .setContentText("$current tracks across $albumCount albums")
                .setOngoing(false)
                .setProgress(0, 0, false)
        } else if (total > 0) {
            builder
                .setContentTitle("Syncing Jellyfin library...")
                .setContentText("$current / $total tracks")
                .setOngoing(true)
                .setProgress(total, current, false)
        } else {
            builder
                .setContentTitle("Syncing Jellyfin library...")
                .setContentText("Preparing...")
                .setOngoing(true)
                .setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(
        current: Int,
        total: Int,
        completed: Boolean = false,
        albumCount: Int = 0
    ) {
        val notification = buildProgressNotification(current, total, completed, albumCount)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sync_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LibrarySyncService"
        private const val CHANNEL_ID = "jellyfin_sync_channel"
        private const val NOTIFICATION_ID = 1002
        private const val BATCH_SIZE = 1000

        /**
         * Starts the sync foreground service from any context (Activity, Fragment, etc.).
         * Safe to call even from the background on Android 12+ due to foreground service
         * start restrictions; the call will be a no-op if the OS blocks it.
         */
        fun startSync(context: Context) {
            try {
                val intent = Intent(context, LibrarySyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start sync service: ${e.message}")
                LibrarySyncManager.updateState(
                    LibrarySyncManager.SyncState.Error("Cannot start sync service: ${e.message}")
                )
            }
        }
    }
}
