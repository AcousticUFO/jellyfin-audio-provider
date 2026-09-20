package com.github.jellyfin_saf.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthByNameRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String
)

@Serializable
data class AuthenticationResult(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("User") val user: UserDto? = null
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null
)

@Serializable
data class ServerSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null
)

@Serializable
data class NameGuidPair(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null
)

@Serializable
data class PersonDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null
)

@Serializable
data class ItemsQueryResult(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0
)

@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("IndexNumber") val trackNumber: Int? = null,
    @SerialName("ParentIndexNumber") val discNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<NameGuidPair>? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("ArtistItems") val artistItems: List<NameGuidPair>? = null,
    @SerialName("People") val people: List<PersonDto>? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("SongCount") val songCount: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("UserData") val userData: UserItemDataDto? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo>? = null,
    @SerialName("HasLyrics") val hasLyrics: Boolean? = null
) {
    val resolvedYear: Int?
        get() = productionYear ?: premiereDate?.take(4)?.toIntOrNull()

    val resolvedDiscNumber: Int?
        get() {
            val candidatePath = path ?: mediaSources?.firstOrNull()?.path
            val combined = listOfNotNull(candidatePath, name, album).joinToString(" ")

            // 1. Check for explicit Disc / CD / LP / Side / Vol >= 2 in path, name, or album
            // (Takes precedence over default ParentIndexNumber=1 if folder/file specifies CD2, Disc 2, etc.)
            val discRegex = Regex("""(?:\b|[^a-zA-Z])(?:disc|disk|cd|lp|vol(?:ume)?|part|pt)[\s_.-]*([0-9]+)""", RegexOption.IGNORE_CASE)
            for (m in discRegex.findAll(combined)) {
                val parsed = m.groupValues[1].toIntOrNull()
                if (parsed != null && parsed >= 2) return parsed
            }

            // Filename pattern like "2-01 - Title" or "12-04 - Title"
            candidatePath?.let { p ->
                val fileName = p.substringAfterLast('\\').substringAfterLast('/')
                val fileMatch = Regex("""^0*([1-9][0-9]?)-([0-9]{1,3})\s*[-_.]""").find(fileName)
                if (fileMatch != null) {
                    val parsed = fileMatch.groupValues[1].toIntOrNull()
                    if (parsed != null && parsed >= 2) return parsed
                }
            }

            // Vinyl Side pattern (Side A/Face A = 1, Side B = 2, Side C = 3, Side D = 4)
            val sideMatch = Regex("""(?:side|face)[\s_.-]*([A-Da-d])\b""", RegexOption.IGNORE_CASE).find(combined)
            if (sideMatch != null) {
                val sideNum = when (sideMatch.groupValues[1].uppercase()) {
                    "A" -> 1
                    "B" -> 2
                    "C" -> 3
                    "D" -> 4
                    else -> 1
                }
                if (sideNum >= 2) return sideNum
            }

            // 2. Direct ParentIndexNumber from Jellyfin server (if > 0)
            if (discNumber != null && discNumber > 0) return discNumber

            // 3. Fallback: Check if explicitly marked as Disc 1
            for (m in discRegex.findAll(combined)) {
                val parsed = m.groupValues[1].toIntOrNull()
                if (parsed != null && parsed > 0) return parsed
            }
            if (sideMatch != null) return 1

            // 4. Default to 1
            return 1
        }

    val resolvedTrackNumber: Int?
        get() {
            if (trackNumber != null && trackNumber > 0) return trackNumber
            val candidatePath = path ?: mediaSources?.firstOrNull()?.path
            val candidate = candidatePath?.substringAfterLast('\\')?.substringAfterLast('/') ?: name ?: return null
            val match = Regex("""^(?:[0-9]+-)?([0-9]{1,3})\s*[-_.]""").find(candidate)
            return match?.groupValues?.get(1)?.toIntOrNull()
        }

    val composerName: String?
        get() = people?.firstOrNull {
            it.type.equals("Composer", ignoreCase = true) || it.role.equals("Composer", ignoreCase = true)
        }?.name
    val durationMs: Long
        get() = (runTimeTicks ?: 0L) / 10_000L

    val resolvedAlbumId: String
        get() = albumId ?: parentId ?: "unknown_album"

    val resolvedAlbumArtist: String
        get() = albumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: artists?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: artistItems?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"

    val artistName: String
        get() = artists?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            ?: artistItems?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            ?: albumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"

    val albumName: String
        get() = album ?: "Unknown Album"

    val mediaSize: Long
        get() = mediaSources?.firstOrNull()?.size ?: 0L

    val mimeType: String
        get() {
            val format = container ?: mediaSources?.firstOrNull()?.container
            return when (format?.lowercase()) {
                "flac" -> "audio/flac"
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/mp4"
                "ogg", "oga", "opus" -> "audio/ogg"
                "wav" -> "audio/wav"
                else -> "audio/mpeg"
            }
        }

    fun calculateRelativePath(libraryRoots: List<String> = emptyList()): Pair<String, String> {
        val p = (path ?: mediaSources?.firstOrNull()?.path)?.replace('\\', '/')?.trim()
        val defaultFileName = (name ?: "Unknown Track") + (if (mimeType.contains("flac")) ".flac" else ".mp3")

        if (p.isNullOrBlank()) {
            val fallbackDir = (resolvedAlbumArtist) + "/" + (album ?: "Unknown Album")
            return Pair(fallbackDir, defaultFileName)
        }

        val cleanRoots = libraryRoots.map { it.replace('\\', '/').trim().trimEnd('/') }.sortedByDescending { it.length }

        for (root in cleanRoots) {
            if (p.startsWith(root, ignoreCase = true)) {
                val rel = p.substring(root.length).trimStart('/')
                val dir = rel.substringBeforeLast('/', "")
                val file = rel.substringAfterLast('/')
                return Pair(dir, if (file.isNotBlank()) file else defaultFileName)
            }
        }

        // Generic fallback if configured library root not matched
        val parts = p.split('/').filter { it.isNotBlank() }
        if (parts.size <= 1) {
            return Pair("", parts.firstOrNull() ?: defaultFileName)
        }

        val file = parts.last()
        val musicIdx = parts.indexOfLast {
            it.equals("Music", ignoreCase = true) ||
            it.equals("Music 2", ignoreCase = true) ||
            it.equals("Audio", ignoreCase = true)
        }

        val relParts = if (musicIdx >= 0 && musicIdx < parts.size - 1) {
            parts.subList(musicIdx + 1, parts.size - 1)
        } else {
            val startIdx = if (parts.first().contains(':')) 1 else 0
            val avail = parts.subList(startIdx, parts.size - 1)
            if (avail.size > 4) avail.takeLast(3) else avail
        }

        val dir = relParts.joinToString("/")
        return Pair(dir, if (file.isNotBlank()) file else defaultFileName)
    }
}

@Serializable
data class UserItemDataDto(
    @SerialName("IsFavorite") val isFavorite: Boolean? = false,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = 0L
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Bitrate") val bitrate: Int? = null
)

@Serializable
data class LyricsResponse(
    @SerialName("Lyrics") val lyrics: List<LyricLineDto>? = null
)

@Serializable
data class LyricLineDto(
    @SerialName("Text") val text: String = "",
    @SerialName("Start") val startTicks: Long = 0L
)

@Serializable
data class VirtualFolderDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Locations") val locations: List<String>? = null
)

