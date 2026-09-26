package com.github.jellyfin_saf.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

data class AlbumSummary(
    val albumId: String,
    val folderName: String?,
    val album: String,
    val albumArtist: String?,
    val artist: String?,
    val trackCount: Int,
    val year: Int?
) {
    val displayName: String
        get() = folderName?.takeIf { it.isNotBlank() } ?: album
}

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrack(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC, title ASC")
    suspend fun getTracksForAlbum(albumId: String): List<TrackEntity>

    @Query("SELECT MAX(discNumber) FROM tracks WHERE albumId = :albumId")
    suspend fun getMaxDiscForAlbum(albumId: String): Int?

    @Query("SELECT DISTINCT relativeDir FROM tracks WHERE relativeDir != ''")
    suspend fun getAllRelativeDirs(): List<String>

    @Query("SELECT * FROM tracks WHERE relativeDir = :relativeDir ORDER BY discNumber ASC, trackNumber ASC, fileName ASC, title ASC")
    suspend fun getTracksInDir(relativeDir: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE relativeDir = :relativeDir OR relativeDir LIKE :prefixPattern LIMIT 1")
    suspend fun getFirstTrackInDirOrSubtree(relativeDir: String, prefixPattern: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE relativeDir = :relativeDir OR relativeDir LIKE :prefixPattern")
    suspend fun getTrackCountInDirOrSubtree(relativeDir: String, prefixPattern: String): Int

    @Query("SELECT albumId, '' as folderName, album, albumArtist, artist, COUNT(*) as trackCount, MAX(year) as year FROM tracks GROUP BY albumId ORDER BY album ASC")
    suspend fun getAllAlbumSummaries(): List<AlbumSummary>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTotalTrackCount(): Int

    @Query("UPDATE tracks SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun updateAccessTime(id: String, timestamp: Long)

    @Query("UPDATE tracks SET isFullyCached = :isFullyCached, cachedBytes = :cachedBytes, lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun updateCacheStatus(id: String, isFullyCached: Boolean, cachedBytes: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM tracks WHERE cachedBytes > 0 AND isFavorite = 0 ORDER BY lastAccessedAt ASC")
    suspend fun getOldestCachedTracksExcludingFavorites(): List<TrackEntity>

    @Query("SELECT COALESCE(SUM(cachedBytes), 0) FROM tracks")
    suspend fun getTotalCachedBytes(): Long

    @Query("SELECT COUNT(*) FROM tracks WHERE cachedBytes > 0")
    suspend fun getCachedTrackCount(): Int

    @Query("SELECT * FROM tracks WHERE isFavorite = 1")
    suspend fun getFavoriteTracks(): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: String)

    @Query("SELECT id FROM tracks")
    suspend fun getAllTrackIds(): List<String>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteTracksByIds(ids: List<String>)

    @Query("UPDATE tracks SET cachedBytes = 0, isFullyCached = 0 WHERE isFavorite = 0")
    suspend fun clearNonFavoriteCaches()

    @Query("DELETE FROM tracks")
    suspend fun clearAll()
}
