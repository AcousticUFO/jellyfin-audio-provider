package com.github.jellyfin_saf.api

import android.content.Context
import android.graphics.Point
import android.util.Log
import com.github.jellyfin_saf.api.model.AuthByNameRequest
import com.github.jellyfin_saf.api.model.AuthenticationResult
import com.github.jellyfin_saf.api.model.ItemsQueryResult
import com.github.jellyfin_saf.api.model.JellyfinItem
import com.github.jellyfin_saf.api.model.LyricsResponse
import com.github.jellyfin_saf.api.model.ServerSystemInfo
import com.github.jellyfin_saf.security.InputValidator
import com.github.jellyfin_saf.security.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Authenticated HTTP client for the Jellyfin REST API.
 * Handles authentication, library queries, audio streaming, and album art downloads.
 */
class JellyfinClient(context: Context) {

    val prefs = SecurePreferences(context)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    private fun getAuthHeader(token: String? = prefs.accessToken): String {
        return "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"Android\", DeviceId=\"${prefs.deviceId}\", Version=\"$CLIENT_VERSION\"" +
                if (!token.isNullOrBlank()) ", Token=\"$token\"" else ""
    }

    /**
     * Pings the server public info endpoint to verify reachability and version.
     */
    suspend fun pingServer(serverUrl: String): Result<ServerSystemInfo> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = InputValidator.normalizeAndValidateServerUrl(serverUrl)
            val request = Request.Builder()
                .url("$normalizedUrl/System/Info/Public")
                .header("Accept", "application/json")
                .header("X-Emby-Authorization", getAuthHeader(token = null))
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                val info = json.decodeFromString<ServerSystemInfo>(body)
                Result.success(info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server ping failed", e)
            Result.failure(e)
        }
    }

    /**
     * Authenticates user credentials with Jellyfin server.
     * Tokens are securely persisted in Android Keystore on success.
     */
    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<AuthenticationResult> =
        withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = InputValidator.normalizeAndValidateServerUrl(serverUrl)
                if (username.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Username cannot be blank"))
                }

                val authBody = json.encodeToString(
                    AuthByNameRequest.serializer(),
                    AuthByNameRequest(username = username, password = password)
                )

                val request = Request.Builder()
                    .url("$normalizedUrl/Users/AuthenticateByName")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Emby-Authorization", getAuthHeader(token = null))
                    .post(authBody.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Authentication failed (HTTP ${response.code})"))
                    }
                    val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
                    val result = json.decodeFromString<AuthenticationResult>(body)

                    val token = result.accessToken
                    val userId = result.user?.id

                    if (token.isNullOrBlank() || userId.isNullOrBlank()) {
                        return@withContext Result.failure(IOException("Server returned incomplete authentication payload"))
                    }

                    // Save verified credentials to Keystore
                    prefs.serverUrl = normalizedUrl
                    prefs.accessToken = token
                    prefs.userId = userId

                    Log.i(TAG, "Authentication successful for user $userId on $normalizedUrl")
                    Result.success(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authentication exception", e)
                Result.failure(e)
            }
        }

    /**
     * Invalidates the remote session and wipes local encrypted credentials.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            val token = prefs.accessToken
            val serverUrl = prefs.serverUrl
            if (!token.isNullOrBlank() && serverUrl.isNotBlank()) {
                val request = Request.Builder()
                    .url("$serverUrl/Sessions/Logout")
                    .header("X-Emby-Authorization", getAuthHeader(token))
                    .post("".toRequestBody(null))
                    .build()
                httpClient.newCall(request).execute().close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote logout call failed (ignoring)", e)
        } finally {
            prefs.clearCredentials()
        }
    }

    /**
     * Queries all Music Libraries on the Jellyfin server.
     */
    suspend fun getMusicLibraries(): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val serverUrl = prefs.serverUrl
        val userId = prefs.userId ?: return@withContext emptyList()
        val token = prefs.accessToken ?: return@withContext emptyList()

        try {
            val url = "$serverUrl/Users/$userId/Views"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Emby-Authorization", getAuthHeader(token))
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val result = json.decodeFromString<ItemsQueryResult>(body)
                // Return collections whose CollectionType is "music" or generic folders
                result.items.filter { it.type == "CollectionFolder" || it.type == "UserView" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching music libraries", e)
            emptyList()
        }
    }

    /**
     * Fetches all music albums for the user.
     */
    suspend fun getAlbums(parentId: String? = null, limit: Int = 10000): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
            val serverUrl = prefs.serverUrl
            val userId = prefs.userId ?: return@withContext emptyList()
            val token = prefs.accessToken ?: return@withContext emptyList()

            try {
                var url = "$serverUrl/Users/$userId/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=SortName&SortOrder=Ascending&Limit=$limit&Fields=$ALBUM_ITEM_FIELDS"
                if (!parentId.isNullOrBlank() && InputValidator.isValidUuid(parentId)) {
                    url += "&ParentId=$parentId"
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("X-Emby-Authorization", getAuthHeader(token))
                    .header("X-Emby-Token", token)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "getAlbums failed: HTTP ${response.code} ${response.message}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val result = json.decodeFromString<ItemsQueryResult>(body)
                    Log.i(TAG, "getAlbums returned ${result.items.size} albums from server.")
                    result.items
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching albums", e)
                emptyList()
            }
        }

    /**
     * Fetches all tracks belonging to an album.
     */
    suspend fun getAlbumTracks(albumId: String): List<JellyfinItem> = withContext(Dispatchers.IO) {
        if (!InputValidator.isValidUuid(albumId)) {
            Log.w(TAG, "getAlbumTracks rejected invalid albumId: $albumId")
            return@withContext emptyList()
        }
        val serverUrl = prefs.serverUrl
        val userId = prefs.userId ?: return@withContext emptyList()
        val token = prefs.accessToken ?: return@withContext emptyList()

        try {
            val url = "$serverUrl/Users/$userId/Items?ParentId=$albumId&IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber,SortName&SortOrder=Ascending&Fields=$AUDIO_ITEM_FIELDS"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Emby-Authorization", getAuthHeader(token))
                .header("X-Emby-Token", token)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "getAlbumTracks failed: HTTP ${response.code} ${response.message}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val result = json.decodeFromString<ItemsQueryResult>(body)
                Log.i(TAG, "getAlbumTracks returned ${result.items.size} tracks for album $albumId")
                result.items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching tracks for album $albumId", e)
            emptyList()
        }
    }

    /**
     * Gets total number of audio tracks available on the server.
     */
    suspend fun getTotalAudioCount(): Int = withContext(Dispatchers.IO) {
        val serverUrl = prefs.serverUrl
        val userId = prefs.userId ?: return@withContext 0
        val token = prefs.accessToken ?: return@withContext 0

        try {
            val url = "$serverUrl/Users/$userId/Items?IncludeItemTypes=Audio&Recursive=true&Limit=0"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Emby-Authorization", getAuthHeader(token))
                .header("X-Emby-Token", token)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val body = response.body?.string() ?: return@withContext 0
                val result = json.decodeFromString<ItemsQueryResult>(body)
                result.totalRecordCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting total audio count", e)
            0
        }
    }

    /**
     * Fetches audio tracks in batches (up to [limit]) recursively across all albums.
     */
    suspend fun getAllAudioTracks(limit: Int = 1000, startIndex: Int = 0): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
            val serverUrl = prefs.serverUrl
            val userId = prefs.userId ?: throw IllegalStateException("User ID not found in secure storage")
            val token = prefs.accessToken ?: throw IllegalStateException("Auth token not found in secure storage")

            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    val url = "$serverUrl/Users/$userId/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&SortOrder=Ascending&Limit=$limit&StartIndex=$startIndex&Fields=$AUDIO_ITEM_FIELDS"
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("X-Emby-Authorization", getAuthHeader(token))
                        .header("X-Emby-Token", token)
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} ${response.message}")
                        }
                        val body = response.body?.string() ?: throw IOException("Empty server response")
                        val result = json.decodeFromString<ItemsQueryResult>(body)
                        return@withContext result.items
                    }
                } catch (e: Exception) {
                    if (attempts >= 2) {
                        Log.e(TAG, "getAllAudioTracks failed after $attempts attempts (start=$startIndex, limit=$limit)", e)
                        throw e
                    }
                    Log.w(TAG, "getAllAudioTracks attempt $attempts failed, retrying...", e)
                }
            }
            emptyList()
        }

    /**
     * Retrieves full metadata for an individual track.
     */
    suspend fun getTrackMetadata(trackId: String): JellyfinItem? = withContext(Dispatchers.IO) {
        if (!InputValidator.isValidUuid(trackId)) return@withContext null
        val serverUrl = prefs.serverUrl
        val userId = prefs.userId ?: return@withContext null
        val token = prefs.accessToken ?: return@withContext null

        try {
            val url = "$serverUrl/Users/$userId/Items/$trackId?Fields=$AUDIO_ITEM_FIELDS"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Emby-Authorization", getAuthHeader(token))
                .header("X-Emby-Token", token)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<JellyfinItem>(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching metadata for track $trackId", e)
            null
        }
    }

    /**
     * Fetches track lyrics (if supported).
     */
    suspend fun getLyrics(trackId: String): String? = withContext(Dispatchers.IO) {
        if (!InputValidator.isValidUuid(trackId)) return@withContext null
        val serverUrl = prefs.serverUrl
        val token = prefs.accessToken ?: return@withContext null

        try {
            val url = "$serverUrl/Audio/$trackId/Lyrics"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Emby-Authorization", getAuthHeader(token))
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<LyricsResponse>(body)
                parsed.lyrics?.joinToString("\n") { it.text }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens an HTTP Range byte stream for audio playback.
     */
    fun openAudioByteStream(trackId: String, startByte: Long, endByte: Long? = null): Response {
        if (!InputValidator.isValidUuid(trackId)) {
            throw IllegalArgumentException("Invalid track ID")
        }
        val serverUrl = prefs.serverUrl
        val token = prefs.accessToken ?: throw IllegalStateException("Not authenticated")

        val streamUrl = "$serverUrl/Audio/$trackId/stream?static=true&api_key=$token"
        val rangeHeader = if (endByte != null) "bytes=$startByte-$endByte" else "bytes=$startByte-"

        val request = Request.Builder()
            .url(streamUrl)
            .header("X-Emby-Authorization", getAuthHeader(token))
            .header("X-Emby-Token", token)
            .header("Range", rangeHeader)
            .get()
            .build()

        return httpClient.newCall(request).execute()
    }

    /**
     * Downloads album cover art directly to an internal target file.
     */
    suspend fun downloadAlbumArt(itemId: String, destinationFile: File, sizeHint: Point?): Boolean =
        withContext(Dispatchers.IO) {
            if (!InputValidator.isValidUuid(itemId)) return@withContext false
            val serverUrl = prefs.serverUrl
            val token = prefs.accessToken ?: return@withContext false

            try {
                var url = "$serverUrl/Items/$itemId/Images/Primary"
                if (sizeHint != null && sizeHint.x > 0 && sizeHint.y > 0) {
                    url += "?maxWidth=${sizeHint.x}&maxHeight=${sizeHint.y}&quality=90"
                }

                val request = Request.Builder()
                    .url(url)
                    .header("X-Emby-Authorization", getAuthHeader(token))
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val inputStream = response.body?.byteStream() ?: return@withContext false
                    FileOutputStream(destinationFile).use { output ->
                        inputStream.copyTo(output)
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download album art for $itemId", e)
                if (destinationFile.exists()) destinationFile.delete()
                false
            }
        }

    suspend fun getLibraryRoots(): List<String> = withContext(Dispatchers.IO) {
        val serverUrl = prefs.serverUrl ?: return@withContext emptyList()
        val token = prefs.accessToken ?: return@withContext emptyList()

        val url = "$serverUrl/Library/VirtualFolders"
        val request = Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", getAuthHeader(token))
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val folders = json.decodeFromString<List<com.github.jellyfin_saf.api.model.VirtualFolderDto>>(body)
                val roots = mutableListOf<String>()
                for (folder in folders) {
                    if (folder.collectionType.equals("music", ignoreCase = true)) {
                        folder.locations?.let { roots.addAll(it) }
                    }
                }
                roots
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch library roots from server: ${e.message}")
            emptyList()
        }
    }


    companion object {
        private const val TAG = "JellyfinClient"
        const val CLIENT_NAME = "Jellyfin Audio Provider"
        const val CLIENT_VERSION = "1.0.0"

        const val AUDIO_ITEM_FIELDS = "ParentIndexNumber,IndexNumber,ProductionYear,PremiereDate,MediaSources,Genres,AlbumArtist,Artists,AlbumArtists,ArtistItems,People,Path"
        const val ALBUM_ITEM_FIELDS = "ChildCount,Genres,MediaSources,AlbumArtist,Artists,AlbumArtists,ArtistItems,ProductionYear,People"
    }
}
