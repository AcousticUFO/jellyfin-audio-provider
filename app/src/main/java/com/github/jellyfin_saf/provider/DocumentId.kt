package com.github.jellyfin_saf.provider

import com.github.jellyfin_saf.security.InputValidator
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Encodes and decodes Storage Access Framework (SAF) Document IDs.
 * Supports arbitrary hierarchical navigation mirroring the Jellyfin server:
 * Root -> Folder (arbitrary depth) -> Track.
 */
sealed class DocumentId {
    object Root : DocumentId()
    data class Folder(val relativeDir: String) : DocumentId()
    data class Album(val albumId: String) : DocumentId()
    data class Track(
        val albumId: String,
        val trackId: String,
        val sizeBytes: Long,
        val durationMs: Long
    ) : DocumentId()

    companion object {
        const val ROOT_ID = "root"
        private const val PREFIX_FOLDER = "dir:"
        private const val PREFIX_ALBUM = "album:"
        private const val PREFIX_TRACK = "track:"

        fun forFolder(relativeDir: String): String {
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(relativeDir.toByteArray(Charsets.UTF_8))
            return "$PREFIX_FOLDER$b64"
        }

        fun forAlbum(albumId: String): String {
            require(InputValidator.isValidUuid(albumId)) { "Invalid album UUID" }
            return "$PREFIX_ALBUM$albumId"
        }

        fun forTrack(albumId: String, trackId: String, sizeBytes: Long, durationMs: Long): String {
            require(InputValidator.isValidUuid(albumId)) { "Invalid album UUID" }
            require(InputValidator.isValidUuid(trackId)) { "Invalid track UUID" }
            return "$PREFIX_TRACK$albumId:$trackId:$sizeBytes:$durationMs"
        }

        fun parse(rawDocId: String): DocumentId {
            var docId = if (rawDocId.contains("%")) {
                try {
                    URLDecoder.decode(rawDocId, "UTF-8")
                } catch (_: Exception) {
                    rawDocId
                }
            } else {
                rawDocId
            }

            if (docId.startsWith("root/")) {
                docId = docId.removePrefix("root/")
            }

            if (docId.contains(PREFIX_TRACK)) {
                val lastIdx = docId.lastIndexOf(PREFIX_TRACK)
                if (lastIdx >= 0) {
                    docId = docId.substring(lastIdx)
                }
            } else if (docId.contains(PREFIX_ALBUM)) {
                val lastIdx = docId.lastIndexOf(PREFIX_ALBUM)
                if (lastIdx >= 0) {
                    docId = docId.substring(lastIdx)
                }
            } else if (docId.contains(PREFIX_FOLDER)) {
                val lastIdx = docId.lastIndexOf(PREFIX_FOLDER)
                if (lastIdx >= 0) {
                    docId = docId.substring(lastIdx)
                }
            }

            return when {
                docId == ROOT_ID -> Root
                docId.startsWith(PREFIX_FOLDER) -> {
                    val rawPayload = docId.removePrefix(PREFIX_FOLDER)
                    val dir = try {
                        val decodedBytes = java.util.Base64.getUrlDecoder().decode(rawPayload)
                        val str = String(decodedBytes, Charsets.UTF_8)
                        if (str.isNotEmpty() && !str.contains('\uFFFD')) str else rawPayload
                    } catch (_: Exception) {
                        try {
                            URLDecoder.decode(rawPayload, "UTF-8")
                        } catch (_: Exception) {
                            rawPayload
                        }
                    }

                    val segments = dir.split('/', '\\')
                    if (dir.contains('\u0000') || segments.any { it.trim() == ".." || it.trim() == "." }) {
                        throw IllegalArgumentException("Invalid folder document ID: path traversal detected")
                    }
                    Folder(dir)
                }
                docId.startsWith(PREFIX_ALBUM) -> {
                    val albumId = docId.removePrefix(PREFIX_ALBUM)
                    if (!InputValidator.isValidUuid(albumId)) {
                        throw IllegalArgumentException("Invalid album document ID: $docId")
                    }
                    Album(albumId)
                }
                docId.startsWith(PREFIX_TRACK) -> {
                    val parts = docId.removePrefix(PREFIX_TRACK).split(":")
                    if (parts.size != 4) {
                        throw IllegalArgumentException("Malformed track document ID: $docId")
                    }
                    val albumId = parts[0]
                    val trackId = parts[1]
                    val size = parts[2].toLongOrNull() ?: 0L
                    val duration = parts[3].substringBefore('.').toLongOrNull() ?: 0L

                    if (!InputValidator.isValidUuid(albumId) || !InputValidator.isValidUuid(trackId)) {
                        throw IllegalArgumentException("Invalid UUIDs in track document ID: $docId")
                    }
                    Track(albumId, trackId, size, duration)
                }
                else -> throw IllegalArgumentException("Unknown document ID format: $docId")
            }
        }
    }
}
