package com.github.jellyfin_saf.cache

import android.content.Context
import android.util.Log
import com.github.jellyfin_saf.db.AppDatabase
import com.github.jellyfin_saf.security.InputValidator
import com.github.jellyfin_saf.security.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.github.jellyfin_saf.stream.ProxyStreamHandler

/**
 * Automated LRU cache manager for streamed audio files.
 * Enforces user storage quotas, guards against path traversal, and automatically
 * prunes least-recently-used audio files when thresholds are exceeded.
 */
class LRUCacheManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val prefs = SecurePreferences(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // App-private sandbox directories (inaccessible to third-party apps)
    val tracksDir: File = File(context.filesDir, "jellyfin_tracks").apply { mkdirs() }
    val thumbsDir: File = File(context.cacheDir, "jellyfin_thumbs").apply { mkdirs() }

    data class CacheStats(
        val totalBytes: Long,
        val maxLimitBytes: Long,
        val cachedSongsCount: Int,
        val albumArtBytes: Long
    ) {
        val usedPercentage: Float
            get() = if (maxLimitBytes > 0) (totalBytes.toFloat() / maxLimitBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }

    /**
     * Safely retrieves the cache file for an audio track.
     * Uses InputValidator to strictly prevent path traversal.
     */
    fun getTrackFile(trackId: String): File {
        return InputValidator.getSafeFile(tracksDir, "$trackId.cache")
    }

    /**
     * Safely deletes the cache file for a specific track, if it exists on disk.
     */
    fun evictTrack(trackId: String) {
        try {
            val file = getTrackFile(trackId)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Evicted cache file for track $trackId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to evict cache file for $trackId", e)
        }
    }

    /**
     * Safely retrieves the cache file for an album thumbnail.
     */
    fun getThumbnailFile(albumId: String): File {
        return InputValidator.getSafeFile(thumbsDir, "$albumId.jpg")
    }

    /**
     * Checks if a track is fully cached on disk.
     */
    suspend fun isTrackFullyCached(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val file = try {
            getTrackFile(trackId)
        } catch (e: Exception) {
            return@withContext false
        }
        if (!file.exists() || file.length() == 0L) return@withContext false

        val entity = db.trackDao().getTrack(trackId) ?: return@withContext false
        entity.isFullyCached && (entity.sizeBytes <= 0L || file.length() == entity.sizeBytes)
    }

    /**
     * Records access to a track and triggers background LRU quota enforcement.
     */
    fun onTrackAccessed(trackId: String) {
        scope.launch {
            try {
                db.trackDao().updateAccessTime(trackId, System.currentTimeMillis())
                enforceCacheQuota()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update track access time", e)
            }
        }
    }

    /**
     * Automatic LRU Eviction Engine:
     * Monitors disk footprint against user preferences. If size breaches max limit,
     * evicts oldest non-favorite tracks until reaching a 15% safety headroom.
     */
    suspend fun enforceCacheQuota(): Int = withContext(Dispatchers.IO) {
        val maxLimitBytes = prefs.maxCacheMb * 1024L * 1024L
        val currentBytes = calculateCurrentUsage()

        if (currentBytes <= maxLimitBytes) {
            return@withContext 0
        }

        Log.i(TAG, "Cache limit exceeded ($currentBytes > $maxLimitBytes bytes). Starting automated LRU eviction.")
        val targetSize = (maxLimitBytes * 0.85).toLong() // 15% headroom
        var freedBytes = 0L
        var evictedCount = 0

        val candidates = db.trackDao().getOldestCachedTracksExcludingFavorites()
        for (track in candidates) {
            if (ProxyStreamHandler.isSessionActive(track.id)) {
                Log.d(TAG, "Skipping active streaming session track from eviction: ${track.id}")
                continue
            }
            if (currentBytes - freedBytes <= targetSize) break

            try {
                val file = getTrackFile(track.id)
                val fileSize = if (file.exists()) file.length() else 0L

                if (file.exists() && file.delete()) {
                    freedBytes += fileSize
                    evictedCount++
                    db.trackDao().updateCacheStatus(track.id, isFullyCached = false, cachedBytes = 0L)
                    Log.d(TAG, "Evicted LRU track ${track.title} ($fileSize bytes)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to evict cache file for ${track.id}", e)
            }
        }

        Log.i(TAG, "Automated LRU eviction complete: pruned $evictedCount tracks ($freedBytes bytes freed)")
        evictedCount
    }

    /**
     * Computes the actual disk usage of cached tracks and thumbnails.
     */
    fun calculateCurrentUsage(): Long {
        val tracksSize = tracksDir.listFiles()?.sumOf { it.length() } ?: 0L
        val thumbsSize = thumbsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return tracksSize + thumbsSize
    }

    suspend fun getStats(): CacheStats = withContext(Dispatchers.IO) {
        val totalUsage = calculateCurrentUsage()
        val limitBytes = prefs.maxCacheMb * 1024L * 1024L
        val songsCount = db.trackDao().getCachedTrackCount()
        val thumbUsage = thumbsDir.listFiles()?.sumOf { it.length() } ?: 0L

        CacheStats(
            totalBytes = totalUsage,
            maxLimitBytes = limitBytes,
            cachedSongsCount = songsCount,
            albumArtBytes = thumbUsage
        )
    }

    /**
     * Purges cached audio tracks and thumbnails.
     */
    suspend fun clearCache(includeFavorites: Boolean = false) = withContext(Dispatchers.IO) {
        if (includeFavorites) {
            tracksDir.listFiles()?.forEach { it.delete() }
            thumbsDir.listFiles()?.forEach { it.delete() }
            db.trackDao().clearAll()
        } else {
            val candidates = db.trackDao().getOldestCachedTracksExcludingFavorites()
            candidates.forEach { track ->
                try {
                    getTrackFile(track.id).delete()
                } catch (_: Exception) {}
            }
            thumbsDir.listFiles()?.forEach { it.delete() }
            db.trackDao().clearNonFavoriteCaches()
        }
    }

    companion object {
        private const val TAG = "LRUCacheManager"
    }
}
