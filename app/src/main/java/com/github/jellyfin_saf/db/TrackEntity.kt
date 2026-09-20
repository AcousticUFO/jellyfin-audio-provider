package com.github.jellyfin_saf.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["albumArtist"]),
        Index(value = ["relativeDir"]),
        Index(value = ["lastAccessedAt"]),
        Index(value = ["isFullyCached"])
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val composer: String? = null,
    val genre: String? = null,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val isFavorite: Boolean = false,
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val isFullyCached: Boolean = false,
    val cachedBytes: Long = 0L,
    val path: String? = null,
    val relativeDir: String = "",
    val fileName: String = ""
)
