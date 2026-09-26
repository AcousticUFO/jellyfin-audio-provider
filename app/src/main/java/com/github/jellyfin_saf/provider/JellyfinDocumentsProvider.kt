package com.github.jellyfin_saf.provider

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import com.github.jellyfin_saf.R
import com.github.jellyfin_saf.api.JellyfinClient
import com.github.jellyfin_saf.cache.LRUCacheManager
import com.github.jellyfin_saf.db.AppDatabase
import com.github.jellyfin_saf.db.TrackEntity
import com.github.jellyfin_saf.stream.ProxyStreamHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Storage Access Framework (SAF) DocumentsProvider for Jellyfin.
 * Exposes remote music albums and tracks to Poweramp and standard Android SAF media players.
 */
class JellyfinDocumentsProvider : DocumentsProvider() {

    private lateinit var client: JellyfinClient
    private lateinit var cacheManager: LRUCacheManager
    private lateinit var db: AppDatabase

    @Volatile
    private var subfolderMap: Map<String, List<String>>? = null

    private val proxyThreads = mutableListOf<HandlerThread>()
    private val proxyHandlers = mutableListOf<Handler>()
    private val nextHandlerIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private fun getSubfolderMap(): Map<String, List<String>> {
        val existing = subfolderMap
        if (existing != null) return existing

        synchronized(this) {
            val doubleCheck = subfolderMap
            if (doubleCheck != null) return doubleCheck

            val allDirs = runBlocking { db.trackDao().getAllRelativeDirs() }
            val map = HashMap<String, MutableSet<String>>()
            map[""] = mutableSetOf()

            for (dir in allDirs) {
                val segments = dir.split('/')
                var currentPath = ""
                for (seg in segments) {
                    map.getOrPut(currentPath) { mutableSetOf() }.add(seg)
                    currentPath = if (currentPath.isEmpty()) seg else "$currentPath/$seg"
                }
            }

            val sortedMap = map.mapValues { it.value.sorted() }
            subfolderMap = sortedMap
            return sortedMap
        }
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        client = JellyfinClient(ctx)
        cacheManager = LRUCacheManager(ctx)
        db = AppDatabase.getInstance(ctx)
        instance = this

        // Initialize proxy thread pool for handling onRead streaming
        synchronized(proxyThreads) {
            if (proxyThreads.isEmpty()) {
                for (i in 0 until 8) {
                    val thread = HandlerThread("JellyfinProxyThread-$i", Process.THREAD_PRIORITY_AUDIO)
                    thread.start()
                    proxyThreads.add(thread)
                    proxyHandlers.add(Handler(thread.looper))
                }
            }
        }

        Log.i(TAG, "JellyfinDocumentsProvider successfully initialized.")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val title = context?.getString(R.string.root_title) ?: "Jellyfin Music"
        val summary = context?.getString(R.string.root_summary) ?: "Your remote Jellyfin music collection"

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentId.ROOT_ID)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "audio/*")
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
            )
            add(DocumentsContract.Root.COLUMN_TITLE, title)
            add(DocumentsContract.Root.COLUMN_SUMMARY, summary)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentId.ROOT_ID)
        }

        return result
    }

    override fun queryDocument(docId: String, projection: Array<out String>?): Cursor {
        val effectiveColumns = if (projection != null) {
            (projection.toList() + DEFAULT_DOCUMENT_PROJECTION.toList()).distinct().toTypedArray()
        } else {
            DEFAULT_DOCUMENT_PROJECTION
        }
        val result = MatrixCursor(effectiveColumns)
        val documentId = try {
            DocumentId.parse(docId)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid document ID requested: $docId", e)
            return result
        }

        when (documentId) {
            is DocumentId.Root -> {
                result.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentId.ROOT_ID)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Jellyfin Music")
                    add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID)
                }
            }

            is DocumentId.Folder -> {
                val folderName = documentId.relativeDir.substringAfterLast('/').ifBlank { "Jellyfin Music" }
                result.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, folderName)
                    add(
                        DocumentsContract.Document.COLUMN_FLAGS,
                        DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                    )
                }
            }

            is DocumentId.Album -> {
                val tracks = runBlocking { db.trackDao().getTracksForAlbum(documentId.albumId) }
                val firstTrack = tracks.firstOrNull()
                val albumTitle = firstTrack?.album ?: "Album"
                result.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, albumTitle)
                    add(
                        DocumentsContract.Document.COLUMN_FLAGS,
                        DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                    )
                    firstTrack?.let {
                        add(MediaStore.Audio.Media.ALBUM, it.album)
                        val artistVal = it.albumArtist?.takeIf { a -> a.isNotBlank() } ?: it.artist
                        add(MediaStore.Audio.Media.ARTIST, artistVal)
                        add(MediaStore.Audio.Media.ALBUM_ARTIST, artistVal)
                    }
                }
            }

            is DocumentId.Track -> {
                try {
                    val cachedTrack = runBlocking { db.trackDao().getTrack(documentId.trackId) }
                    val metadata = if (cachedTrack != null && cachedTrack.discNumber != null) {
                        cachedTrack
                    } else {
                        runBlocking {
                            val item = client.getTrackMetadata(documentId.trackId)
                            item?.let {
                                val libraryRoots = client.getLibraryRoots()
                                val (relDir, fileName) = it.calculateRelativePath(libraryRoots)
                                val entity = TrackEntity(
                                    id = it.id,
                                    albumId = documentId.albumId,
                                    title = it.name ?: "Unknown Title",
                                    artist = it.artistName,
                                    album = it.albumName,
                                    albumArtist = it.resolvedAlbumArtist,
                                    composer = it.composerName,
                                    genre = it.genres?.filter { g -> g.isNotBlank() }?.joinToString(", ")?.takeIf { g -> g.isNotBlank() },
                                    durationMs = it.durationMs,
                                    sizeBytes = documentId.sizeBytes,
                                    mimeType = it.mimeType,
                                    year = it.resolvedYear,
                                    trackNumber = it.resolvedTrackNumber,
                                    discNumber = it.resolvedDiscNumber,
                                    isFavorite = it.userData?.isFavorite ?: false,
                                    path = it.path ?: it.mediaSources?.firstOrNull()?.path,
                                    relativeDir = relDir,
                                    fileName = fileName
                                )
                                db.trackDao().insertOrUpdate(entity)
                                entity
                            } ?: cachedTrack
                        }
                    }

                    if (metadata != null) {
                        val lyrics = runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(500L) {
                                client.getLyrics(documentId.trackId)
                            }
                        }
                        val maxDisc = runBlocking { db.trackDao().getMaxDiscForAlbum(documentId.albumId) } ?: 1
                        addTrackRow(result, metadata, maxDisc, lyrics, docIdOverride = docId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying track document $docId", e)
                }
            }
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val effectiveColumns = if (projection != null) {
            (projection.toList() + DEFAULT_DOCUMENT_PROJECTION.toList()).distinct().toTypedArray()
        } else {
            DEFAULT_DOCUMENT_PROJECTION
        }
        val result = MatrixCursor(effectiveColumns)
        val parentId = try {
            DocumentId.parse(parentDocumentId)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid parent document ID: $parentDocumentId", e)
            return result
        }

        try {
            when (parentId) {
                is DocumentId.Root -> {
                    val treeMap = getSubfolderMap()
                    val topFolders = treeMap[""] ?: emptyList()
                    if (topFolders.isNotEmpty()) {
                        topFolders.forEach { folderName ->
                            result.newRow().apply {
                                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentId.forFolder(folderName))
                                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, folderName)
                                add(
                                    DocumentsContract.Document.COLUMN_FLAGS,
                                    DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                                )
                            }
                        }

                        val rootTracks = runBlocking { db.trackDao().getTracksInDir("") }
                        rootTracks.forEach { track ->
                            addTrackRow(result, track)
                        }
                    } else {
                        val albums = runBlocking { client.getAlbums() }
                        albums.forEach { album ->
                            val artistDisplay = album.resolvedAlbumArtist
                            result.newRow().apply {
                                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentId.forAlbum(album.id))
                                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, album.name ?: "Unknown Album")
                                add(DocumentsContract.Document.COLUMN_SUMMARY, "${album.songCount ?: 0} Songs • $artistDisplay")
                                add(
                                    DocumentsContract.Document.COLUMN_FLAGS,
                                    DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                                )
                                add(MediaStore.Audio.Media.ALBUM, album.name ?: "Unknown Album")
                                add(MediaStore.Audio.Media.ARTIST, artistDisplay)
                                add(MediaStore.Audio.Media.ALBUM_ARTIST, artistDisplay)
                                add("album_artist", artistDisplay)
                                add("albumartist", artistDisplay)
                            }
                        }
                    }
                }

                is DocumentId.Folder -> {
                    val currentDir = parentId.relativeDir
                    val treeMap = getSubfolderMap()
                    val subFolders = treeMap[currentDir] ?: emptyList()

                    subFolders.forEach { subFolder ->
                        val childRelativeDir = "$currentDir/$subFolder"
                        result.newRow().apply {
                            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentId.forFolder(childRelativeDir))
                            add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, subFolder)
                            add(
                                DocumentsContract.Document.COLUMN_FLAGS,
                                DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                            )
                        }
                    }

                    val tracks = runBlocking { db.trackDao().getTracksInDir(currentDir) }
                    val maxDisc = tracks.maxOfOrNull { (it.discNumber ?: 1).coerceAtLeast(1) } ?: 1
                    tracks.forEach { track ->
                        addTrackRow(result, track, maxDisc)
                    }
                }

                is DocumentId.Album -> {
                    val tracks = runBlocking { db.trackDao().getTracksForAlbum(parentId.albumId) }
                    val maxDisc = tracks.maxOfOrNull { (it.discNumber ?: 1).coerceAtLeast(1) } ?: 1
                    tracks.forEach { track ->
                        addTrackRow(result, track, maxDisc)
                    }
                }

                else -> {
                    Log.w(TAG, "queryChildDocuments called on non-directory: $parentDocumentId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying child documents for $parentDocumentId", e)
        }

        return result
    }

    private fun addTrackRow(
        result: MatrixCursor,
        track: TrackEntity,
        maxDiscInAlbum: Int = 1,
        lyrics: String? = null,
        docIdOverride: String? = null
    ) {
        val disc = (track.discNumber ?: 1).coerceAtLeast(1)
        val trk = (track.trackNumber ?: 0).coerceAtLeast(0)
        val totalDiscs = maxOf(maxDiscInAlbum, disc, 1)
        val trackFileName = if (track.fileName.isNotBlank()) {
            track.fileName
        } else {
            getTrackDisplayName(track.title, track.mimeType, trk, disc, totalDiscs)
        }

        val docId = docIdOverride ?: DocumentId.forTrack(
            albumId = track.albumId,
            trackId = track.id,
            sizeBytes = track.sizeBytes,
            durationMs = track.durationMs
        )

        result.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, track.mimeType)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, trackFileName)
            add(DocumentsContract.Document.COLUMN_SIZE, track.sizeBytes)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, track.lastAccessedAt)
            add(
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
            )

            // Pure MediaStore & Poweramp Metadata (unmodified tags)
            add(MediaStore.Audio.Media.TITLE, track.title)
            add(MediaStore.Audio.Media.ARTIST, track.artist)
            add(MediaStore.Audio.Media.ALBUM, track.album)
            val albumArtistVal = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
            add(MediaStore.Audio.Media.ALBUM_ARTIST, albumArtistVal)
            add("album_artist", albumArtistVal)
            add("albumartist", albumArtistVal)
            if (!track.composer.isNullOrBlank()) {
                add(MediaStore.Audio.Media.COMPOSER, track.composer)
                add("composer", track.composer)
            }
            if (!track.genre.isNullOrBlank()) {
                add(MediaStore.Audio.Media.GENRE, track.genre)
                add("genre", track.genre)
            }
            add(MediaStore.Audio.Media.DURATION, track.durationMs)
            add(MediaStore.Audio.Media.YEAR, track.year ?: 0)
            add("year", track.year ?: 0)
            add("date", track.year ?: 0)

            // Disc tags
            add(MediaStore.Audio.Media.DISC_NUMBER, disc)
            add("disc_number", disc)
            add("discnumber", disc)
            add("disc_no", disc)
            add("disc", disc)
            add("cd", disc)
            add("cd_number", disc)
            add("disc_num", disc)
            add("part_of_a_set", "$disc/$totalDiscs")
            add("tpos", "$disc/$totalDiscs")
            add("TPOS", "$disc/$totalDiscs")
            add("disc_total", totalDiscs)
            add("discs_total", totalDiscs)
            add("totaldiscs", totalDiscs)
            add("total_discs", totalDiscs)
            add("num_discs", totalDiscs)

            // Track tags
            val compoundTrack = if (disc >= 2 && trk > 0) (disc * 1000 + trk) else trk
            add(MediaStore.Audio.Media.TRACK, compoundTrack)
            add(MediaStore.Audio.AudioColumns.TRACK, compoundTrack)
            add("track", compoundTrack)
            add("track_number", compoundTrack)
            add("tracknumber", compoundTrack)
            add("track_no", compoundTrack)
            add("track_tag", trk)
            add("track_number_alt", trk)
            add(MediaStore.Audio.Media.IS_MUSIC, 1)

            if (!lyrics.isNullOrBlank()) {
                add(COLUMN_TRACK_LYRICS, lyrics)
            }

            add(POWERAMP_COLUMN_FLAGS, 0x1)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val parsedId = DocumentId.parse(documentId)
        if (parsedId !is DocumentId.Track) {
            throw IOException("Only track documents can be opened for audio playback. Requested: $documentId")
        }

        val ctx = context ?: throw IOException("Context unavailable")

        // 1. Check if fully cached locally
        val cacheFile = cacheManager.getTrackFile(parsedId.trackId)
        val isFullyCached = runBlocking { cacheManager.isTrackFullyCached(parsedId.trackId) }

        if (isFullyCached && cacheFile.exists()) {
            Log.d(TAG, "[${parsedId.trackId}] Serving directly from local cache on disk")
            cacheManager.onTrackAccessed(parsedId.trackId)
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // 2. Otherwise, open via ProxyFileDescriptor for real-time HTTP Range streaming
        Log.d(TAG, "[${parsedId.trackId}] Opening on-demand streaming proxy")
        val storageManager = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val streamHandler = ProxyStreamHandler(
            context = ctx,
            trackId = parsedId.trackId,
            totalSizeBytes = parsedId.sizeBytes,
            client = client,
            cacheManager = cacheManager
        )

        signal?.setOnCancelListener {
            streamHandler.onRelease()
        }

        val handlerIndex = nextHandlerIndex.getAndIncrement() % proxyHandlers.size
        val handler = proxyHandlers[handlerIndex]

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            streamHandler,
            handler
        )
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val albumId = when (val parsed = DocumentId.parse(documentId)) {
            is DocumentId.Album -> parsed.albumId
            is DocumentId.Track -> parsed.albumId
            is DocumentId.Folder -> {
                val firstTrack = runBlocking {
                    db.trackDao().getFirstTrackInDirOrSubtree(parsed.relativeDir, "${parsed.relativeDir}/%")
                }
                firstTrack?.albumId ?: throw FileNotFoundException("No tracks found in folder ${parsed.relativeDir}")
            }
            else -> throw IOException("Thumbnails not supported for document $documentId")
        }

        val thumbFile = cacheManager.getThumbnailFile(albumId)

        if (!thumbFile.exists()) {
            val success = runBlocking {
                client.downloadAlbumArt(albumId, thumbFile, sizeHint)
            }
            if (!success || !thumbFile.exists()) {
                throw FileNotFoundException("Thumbnail not found for album $albumId")
            }
        }

        val pfd = ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return true
        if (parentDocumentId == DocumentId.ROOT_ID) return true

        val parent = try { DocumentId.parse(parentDocumentId) } catch (_: Exception) { return false }
        val doc = try { DocumentId.parse(documentId) } catch (_: Exception) { return false }

        return when {
            parent is DocumentId.Root -> true
            parent is DocumentId.Folder && doc is DocumentId.Folder -> {
                doc.relativeDir.startsWith("${parent.relativeDir}/")
            }
            parent is DocumentId.Folder && doc is DocumentId.Track -> {
                val track = runBlocking { db.trackDao().getTrack(doc.trackId) }
                track != null && (track.relativeDir == parent.relativeDir || track.relativeDir.startsWith("${parent.relativeDir}/"))
            }
            parent is DocumentId.Album && doc is DocumentId.Track -> {
                parent.albumId == doc.albumId
            }
            else -> false
        }
    }

    override fun shutdown() {
        synchronized(proxyThreads) {
            proxyThreads.forEach { it.quitSafely() }
            proxyThreads.clear()
            proxyHandlers.clear()
        }
        super.shutdown()
    }

    companion object {
        private const val TAG = "JellyfinDocumentsProvider"

        @Volatile
        private var instance: JellyfinDocumentsProvider? = null

        fun invalidateCache() {
            instance?.subfolderMap = null
            try {
                val ctx = instance?.context
                if (ctx != null) {
                    val rootUri = DocumentsContract.buildRootsUri(ctx.getString(R.string.documents_authority))
                    ctx.contentResolver.notifyChange(rootUri, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify root change", e)
            }
        }

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,

            // MediaStore & Poweramp Metadata
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            "album_artist",
            "albumartist",
            MediaStore.Audio.Media.COMPOSER,
            "composer",
            MediaStore.Audio.Media.GENRE,
            "genre",
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            "year",
            "date",
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.AudioColumns.TRACK,
            "track",
            "track_number",
            "tracknumber",
            "track_no",
            "track_tag",
            "track_number_alt",
            MediaStore.Audio.Media.DISC_NUMBER,
            "disc_number",
            "discnumber",
            "disc_no",
            "disc",
            "cd",
            "cd_number",
            "disc_num",
            "disc_total",
            "discs_total",
            "totaldiscs",
            "total_discs",
            "num_discs",
            "part_of_a_set",
            "tpos",
            "TPOS",
            MediaStore.Audio.Media.IS_MUSIC,

            // Lyrics
            "lyrics",
            "lyrics_synced",

            // Poweramp Flags (0x1 = Thumbnail supported)
            "com.maxmpz.poweramp.provider.COLUMN_FLAGS"
        )

        private const val COLUMN_TRACK_LYRICS = "lyrics"
        private const val POWERAMP_COLUMN_FLAGS = "com.maxmpz.poweramp.provider.COLUMN_FLAGS"

        fun getTrackDisplayName(
            title: String,
            mimeType: String,
            trackNumber: Int? = null,
            discNumber: Int? = null,
            totalDiscs: Int = 1
        ): String {
            val ext = when (mimeType.lowercase()) {
                "audio/flac" -> ".flac"
                "audio/mpeg", "audio/mp3" -> ".mp3"
                "audio/mp4", "audio/m4a", "audio/aac" -> ".m4a"
                "audio/ogg", "audio/opus" -> ".ogg"
                "audio/wav", "audio/x-wav" -> ".wav"
                else -> ".flac"
            }
            val cleanTitle = title.removeSuffix(ext)
                .replaceFirst(Regex("""^[0-9]+-[0-9]+[\s_.-]+"""), "") // removes "1-01 - " or "2-01 - "
                .replaceFirst(Regex("""^[0-9]+[\s_.-]+"""), "") // removes "01 - " or "01. "
                .trim()
            val finalTitle = if (cleanTitle.isNotBlank()) cleanTitle else title.removeSuffix(ext)

            val disc = discNumber ?: 1
            val trk = trackNumber ?: 0

            val prefix = when {
                disc > 0 && trk > 0 -> "$disc-${trk.toString().padStart(2, '0')} - "
                disc > 1 -> "$disc - "
                trk > 0 -> "${trk.toString().padStart(2, '0')} - "
                else -> ""
            }

            return "$prefix$finalTitle$ext"
        }
    }
}
