package com.github.jellyfin_saf.stream

import android.content.Context
import android.os.PowerManager
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.github.jellyfin_saf.api.JellyfinClient
import com.github.jellyfin_saf.cache.LRUCacheManager
import com.github.jellyfin_saf.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response

/**
 * Standardized, resilient virtual file streaming bridge between Jellyfin and Android SAF.
 *
 * Implements a robust contiguous streaming and caching architecture:
 * - Single unified FileChannel under strict thread and memory synchronization (fileLock).
 * - Full-read guarantee: onRead loops and blocks until the exact requested buffer is filled or true EOF is reached.
 * - Absolute prohibition of short reads and EAGAIN errors to prevent native FLAC decoder filter saturation (static noise bursts).
 * - Clean sequential background downloader with automatic HTTP Range reconnect and exponential backoff.
 * - Clean seek repositioning without creating sparse zero-holes.
 * - Session keep-alive grace period preventing premature cache deletion during player probe cycles.
 */
class ProxyStreamHandler(
    private val context: Context,
    private val trackId: String,
    totalSizeBytes: Long,
    private val client: JellyfinClient,
    private val cacheManager: LRUCacheManager
) : ProxyFileDescriptorCallback() {

    private val db = AppDatabase.getInstance(context)
    private val session: TrackSession = getOrCreateSession(
        trackId = trackId,
        totalSizeBytes = totalSizeBytes,
        context = context,
        client = client,
        cacheManager = cacheManager,
        db = db
    )

    @Volatile
    private var isReleased = false

    init {
        if (session.totalSizeBytes <= 0) {
            session.probeTotalSize()
        }
        // Pre-buffer initial header bytes (2 MB) for instantaneous decoding and Hi-Res transition headroom
        val initialPrebuffer = minOf(2 * 1024 * 1024L, if (session.totalSizeBytes > 0) session.totalSizeBytes else 2 * 1024 * 1024L)
        session.ensureBytesAvailable(0L, initialPrebuffer.toInt(), timeoutMs = 10000L)
        cacheManager.onTrackAccessed(trackId)
    }

    override fun onGetSize(): Long {
        val size = session.totalSizeBytes
        if (size <= 0) {
            session.probeTotalSize()
            Log.d(TAG, "[$trackId] onGetSize after probe: size=${session.totalSizeBytes}")
        }
        return session.totalSizeBytes
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset < 0L || size <= 0 || isReleased || session.isReleased) {
            return 0
        }

        val totalSize = session.totalSizeBytes
        if (totalSize > 0 && offset >= totalSize) {
            return 0
        }

        val targetLen = if (totalSize > 0) minOf(size.toLong(), totalSize - offset).toInt() else size
        if (targetLen <= 0) return 0

        // Android FUSE contract: onRead MUST return exactly targetLen bytes unless EOF is reached.
        // Returning fewer bytes causes FUSE to signal premature EOF, corrupting the demuxer.
        // Throwing EAGAIN on a regular file descriptor causes native audio decoders (FFmpeg in Poweramp)
        // to treat the packet as corrupted, leading to explosive 0 dBFS white noise bursts.
        val ready = session.ensureBytesAvailable(offset, targetLen, timeoutMs = 30000L)
        if (!ready) {
            if (session.streamingError) {
                Log.e(TAG, "[$trackId] Unrecoverable stream error at offset $offset")
                throw ErrnoException("onRead", OsConstants.EIO)
            }
            Log.e(TAG, "[$trackId] Stream timeout waiting for $targetLen bytes at offset $offset")
            throw ErrnoException("onRead", OsConstants.ETIMEDOUT)
        }

        val bytesRead = session.readBytes(offset, targetLen, data)
        if (bytesRead < targetLen && (totalSize <= 0 || offset + bytesRead < totalSize)) {
            Log.e(TAG, "[$trackId] Incomplete read: got $bytesRead of $targetLen at offset $offset")
            throw ErrnoException("onRead", OsConstants.EIO)
        }

        return bytesRead
    }

    override fun onRelease() {
        if (isReleased) return
        isReleased = true
        Log.d(TAG, "[$trackId] Proxy handle released.")
        releaseSession(trackId)
    }

    /**
     * Internal streaming session managing background downloads, interval tracking,
     * and synchronized file channel operations for a specific track.
     */
    internal class TrackSession(
        val trackId: String,
        @Volatile var totalSizeBytes: Long,
        val cacheFile: File,
        internal val context: Context,
        private val client: JellyfinClient,
        private val cacheManager: LRUCacheManager,
        private val db: AppDatabase
    ) {
        val intervals = IntervalSet()
        val streamLock = Any()
        val fileLock = Any()
        val directRangeLock = Any()
        val refCount = AtomicInteger(0)

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val fileRaf: RandomAccessFile
        private val fileChannel: FileChannel

        private var wakeLock: PowerManager.WakeLock? = null

        @Volatile var isReleased = false
        @Volatile var currentDownloadOffset: Long = 0L
        @Volatile var streamingError = false
        private var downloadJob: Job? = null
        @Volatile private var currentResponse: Response? = null
        private var cleanupJob: Job? = null

        init {
            val isFullyCached = runBlocking { cacheManager.isTrackFullyCached(trackId) }
            if (!cacheFile.exists()) {
                cacheFile.createNewFile()
            } else if (!isFullyCached) {
                // Clean any partial or stale remnants from previous sessions to guarantee zero sparse gaps
                try {
                    cacheFile.delete()
                    cacheFile.createNewFile()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reinitialize cache file for $trackId", e)
                }
            }

            fileRaf = RandomAccessFile(cacheFile, "rw")
            fileChannel = fileRaf.channel

            wakeLock = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JellyfinAudioProvider:Session-$trackId")?.apply {
                    setReferenceCounted(false)
                    acquire(15 * 60 * 1000L)
                }
            } catch (_: Exception) {
                null
            }

            if (totalSizeBytes <= 0) {
                probeTotalSize()
            }

            if (isFullyCached) {
                val fileLen = cacheFile.length()
                if (fileLen > 0) {
                    totalSizeBytes = fileLen
                    intervals.add(0L, fileLen)
                }
            } else {
                startDownloadStream(0L)
            }

            // Pre-fetch lyrics asynchronously into local disk cache
            scope.launch {
                try {
                    if (cacheManager.getCachedLyrics(trackId) == null) {
                        val lrc = client.getLyrics(trackId)
                        if (!lrc.isNullOrBlank()) {
                            cacheManager.saveLyrics(trackId, lrc)
                            Log.d(TAG, "[$trackId] Pre-cached synchronized lyrics.")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[$trackId] Could not pre-cache lyrics: ${e.message}")
                }
            }
        }

        fun scheduleGracefulCleanup(delayMs: Long, onExpire: () -> Unit) {
            synchronized(this) {
                cleanupJob?.cancel()
                cleanupJob = scope.launch {
                    delay(delayMs)
                    onExpire()
                }
            }
        }

        fun cancelGracefulCleanup() {
            synchronized(this) {
                cleanupJob?.cancel()
                cleanupJob = null
            }
        }

        /**
         * Reads bytes directly from the synchronized file channel into [data].
         * Fully thread-safe with writes, enforcing cross-core memory visibility.
         */
        fun readBytes(offset: Long, targetLen: Int, data: ByteArray): Int {
            synchronized(fileLock) {
                val bb = ByteBuffer.wrap(data, 0, targetLen)
                var pos = offset
                while (bb.hasRemaining()) {
                    val n = fileChannel.read(bb, pos)
                    if (n <= 0) break
                    pos += n
                }
                return bb.position()
            }
        }

        /**
         * Ensures that the requested byte range [offset, offset + neededBytes) is present in the cache.
         * Blocks safely on streamLock until bytes arrive or timeout is reached.
         */
        fun ensureBytesAvailable(offset: Long, neededBytes: Int, timeoutMs: Long = 30000L): Boolean {
            if (isReleased) return false
            val total = totalSizeBytes
            val targetLen = if (total > 0) minOf(neededBytes.toLong(), total - offset).toInt() else neededBytes
            if (targetLen <= 0) return true

            // 1. Fast path: check if already downloaded
            val currentAvail = intervals.getAvailableLengthFrom(offset)
            if (currentAvail >= targetLen || (total > 0 && offset + currentAvail >= total)) {
                return true
            }

            // 2. Check if this is an EOF footer metadata probe (e.g. ID3v1 / FLAC seektable in the last 512KB)
            val isEndOfFileProbe = total > 512 * 1024L && offset >= (total - 512 * 1024L)
            if (isEndOfFileProbe) {
                val footerLen = minOf(total - offset, 256 * 1024L)
                fetchDirectRange(offset, footerLen)
                val availAfterFooter = intervals.getAvailableLengthFrom(offset)
                if (availAfterFooter >= targetLen || (total > 0 && offset + availAfterFooter >= total)) {
                    return true
                }
            } else {
                // Streaming path: reposition download stream only on true seek
                val currentDl = currentDownloadOffset
                val isSeek = (offset < currentDl - 256 * 1024L) || (offset > currentDl + 4 * 1024 * 1024L)
                if (isSeek) {
                    Log.d(TAG, "[$trackId] Seek jump detected: read at $offset (downloader at $currentDl). Repositioning stream.")
                    startDownloadStream(offset)
                } else if (downloadJob?.isActive != true && (total <= 0 || !intervals.contains(0, total))) {
                    Log.d(TAG, "[$trackId] Downloader inactive at $currentDl. Restarting from $offset.")
                    startDownloadStream(offset)
                }
            }

            // 3. Blocking wait until bytes are written and flushed
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(streamLock) {
                while (!isReleased && System.currentTimeMillis() < deadline) {
                    val avail = intervals.getAvailableLengthFrom(offset)
                    if (avail >= targetLen || (total > 0 && offset + avail >= total)) {
                        return true
                    }
                    if (streamingError) {
                        return false
                    }
                    try {
                        (streamLock as Object).wait(200)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
                val finalAvail = intervals.getAvailableLengthFrom(offset)
                return finalAvail >= targetLen || (total > 0 && offset + finalAvail >= total)
            }
        }

        @Synchronized
        fun startDownloadStream(offset: Long) {
            if (isReleased) return

            try {
                currentResponse?.close()
            } catch (_: Exception) {}
            currentResponse = null

            downloadJob?.cancel()
            currentDownloadOffset = offset
            streamingError = false

            downloadJob = scope.launch {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                var fetchOffset = offset
                var consecutiveErrors = 0
                val maxRetries = 10

                while (isActive && !isReleased) {
                    val alreadyAvail = intervals.getAvailableLengthFrom(fetchOffset)
                    if (alreadyAvail > 0) {
                        fetchOffset += alreadyAvail
                        currentDownloadOffset = fetchOffset
                        if (totalSizeBytes > 0 && fetchOffset >= totalSizeBytes) {
                            checkAndMarkFullyCached()
                            break
                        }
                        continue
                    }

                    var response: Response? = null
                    try {
                        Log.d(TAG, "[$trackId] Opening download stream at offset $fetchOffset (attempt ${consecutiveErrors + 1})")
                        response = client.openAudioByteStream(trackId, fetchOffset)
                        currentResponse = response

                        if (!response.isSuccessful && response.code != 206) {
                            Log.w(TAG, "[$trackId] Stream request returned HTTP ${response.code}")
                            if (response.code == 416) {
                                probeTotalSize()
                                break
                            }
                            throw IOException("HTTP ${response.code}: ${response.message}")
                        }

                        consecutiveErrors = 0
                        streamingError = false

                        val cr = response.header("Content-Range")
                        val totalFromRange = cr?.substringAfterLast('/')?.toLongOrNull()
                        val cl = response.header("Content-Length")?.toLongOrNull()
                        val serverTotal = totalFromRange ?: (if (fetchOffset == 0L && cl != null && cl > 0) cl else null)
                        if (serverTotal != null && serverTotal > 0 && serverTotal != totalSizeBytes) {
                            totalSizeBytes = serverTotal
                        }

                        val rawStream = response.body?.byteStream() ?: throw IOException("Empty response body")
                        val stream = BufferedInputStream(rawStream, 256 * 1024)
                        val buffer = ByteArray(128 * 1024)

                        while (isActive && !isReleased) {
                            val read = stream.read(buffer)
                            if (read <= 0) {
                                if (totalSizeBytes <= 0) {
                                    totalSizeBytes = fetchOffset
                                }
                                checkAndMarkFullyCached()
                                return@launch
                            }

                            synchronized(fileLock) {
                                val bb = ByteBuffer.wrap(buffer, 0, read)
                                var pos = fetchOffset
                                while (bb.hasRemaining()) {
                                    val written = fileChannel.write(bb, pos)
                                    if (written <= 0) break
                                    pos += written
                                }
                            }

                            synchronized(streamLock) {
                                intervals.add(fetchOffset, fetchOffset + read)
                                (streamLock as Object).notifyAll()
                            }

                            fetchOffset += read
                            currentDownloadOffset = fetchOffset

                            if (totalSizeBytes > 0 && intervals.contains(0, totalSizeBytes)) {
                                checkAndMarkFullyCached()
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            break
                        }
                        consecutiveErrors++
                        Log.w(TAG, "[$trackId] Stream error at $fetchOffset (retry $consecutiveErrors/$maxRetries): ${e.message}")

                        if (consecutiveErrors >= maxRetries) {
                            Log.e(TAG, "[$trackId] Max stream retries reached at $fetchOffset. Signaling stream error.")
                            streamingError = true
                            synchronized(streamLock) {
                                (streamLock as Object).notifyAll()
                            }
                            break
                        }

                        val delayMs = (500L * (1L shl (consecutiveErrors - 1))).coerceAtMost(5000L)
                        delay(delayMs)
                    } finally {
                        try {
                            response?.close()
                        } catch (_: Exception) {}
                        if (currentResponse === response) {
                            currentResponse = null
                        }
                    }
                }
            }
        }

        fun fetchDirectRange(start: Long, requestedLength: Long) {
            if (isReleased) return
            val total = totalSizeBytes
            val endInclusive = if (total > 0) {
                minOf(total - 1L, start + requestedLength - 1L)
            } else {
                start + requestedLength - 1L
            }
            if (start > endInclusive) return

            val needed = minOf(requestedLength, if (total > 0) total - start else requestedLength)
            if (intervals.getAvailableLengthFrom(start) >= needed) return

            synchronized(directRangeLock) {
                if (intervals.getAvailableLengthFrom(start) >= needed) return

                try {
                    Log.d(TAG, "[$trackId] Direct range fetch: $start..$endInclusive")
                    client.openAudioByteStream(trackId, start, endInclusive).use { response ->
                        if (!response.isSuccessful && response.code != 206) {
                            Log.w(TAG, "[$trackId] Direct range fetch HTTP ${response.code}")
                            if (response.code == 416) {
                                probeTotalSize()
                            }
                            return
                        }
                        val cr = response.header("Content-Range")
                        val totalFromRange = cr?.substringAfterLast('/')?.toLongOrNull()
                        if (totalFromRange != null && totalFromRange > 0 && totalFromRange != totalSizeBytes) {
                            totalSizeBytes = totalFromRange
                        }
                        val rawStream = response.body?.byteStream() ?: return
                        val stream = BufferedInputStream(rawStream, 128 * 1024)
                        val buf = ByteArray(64 * 1024)
                        var current = start
                        while (!isReleased) {
                            val read = stream.read(buf)
                            if (read <= 0) break

                            synchronized(fileLock) {
                                val bb = ByteBuffer.wrap(buf, 0, read)
                                var pos = current
                                while (bb.hasRemaining()) {
                                    val written = fileChannel.write(bb, pos)
                                    if (written <= 0) break
                                    pos += written
                                }
                            }
                            synchronized(streamLock) {
                                intervals.add(current, current + read)
                                (streamLock as Object).notifyAll()
                            }
                            current += read
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[$trackId] Direct range fetch exception ($start..$endInclusive): ${e.message}")
                }
            }
        }

        fun probeTotalSize() {
            try {
                client.openAudioByteStream(trackId, 0, 1).use { response ->
                    val cr = response.header("Content-Range")
                    val total = cr?.substringAfterLast('/')?.toLongOrNull()
                    if (total != null && total > 0) {
                        totalSizeBytes = total
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$trackId] Failed to probe total audio size", e)
            }
        }

        private fun checkAndMarkFullyCached() {
            if (totalSizeBytes > 0 && intervals.contains(0, totalSizeBytes)) {
                scope.launch {
                    try {
                        synchronized(fileLock) {
                            fileChannel.force(true)
                        }
                        db.trackDao().updateCacheStatus(
                            id = trackId,
                            isFullyCached = true,
                            cachedBytes = totalSizeBytes
                        )
                        Log.i(TAG, "[$trackId] Track fully downloaded ($totalSizeBytes bytes) and cached on disk.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update cache status in DB", e)
                    }
                }
            }
        }

        fun close() {
            if (isReleased) return
            isReleased = true
            Log.d(TAG, "[$trackId] Closing session and releasing resources.")

            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}

            try {
                currentResponse?.close()
            } catch (_: Exception) {}
            currentResponse = null

            cleanupJob?.cancel()
            downloadJob?.cancel()
            scope.cancel()

            synchronized(streamLock) {
                (streamLock as Object).notifyAll()
            }

            synchronized(fileLock) {
                try {
                    fileChannel.close()
                    fileRaf.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing session file channel", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProxyStreamHandler"
        private val activeSessions = ConcurrentHashMap<String, TrackSession>()

        fun isSessionActive(trackId: String): Boolean {
            return activeSessions.containsKey(trackId)
        }

        @Synchronized
        internal fun getOrCreateSession(
            trackId: String,
            totalSizeBytes: Long,
            context: Context,
            client: JellyfinClient,
            cacheManager: LRUCacheManager,
            db: AppDatabase
        ): TrackSession {
            val existing = activeSessions[trackId]
            if (existing != null && !existing.isReleased) {
                existing.cancelGracefulCleanup()
                existing.refCount.incrementAndGet()
                if (existing.totalSizeBytes <= 0 && totalSizeBytes > 0) {
                    existing.totalSizeBytes = totalSizeBytes
                }
                Log.d(TAG, "[$trackId] Reusing active session (refCount=${existing.refCount.get()})")
                return existing
            }

            val session = TrackSession(
                trackId = trackId,
                totalSizeBytes = totalSizeBytes,
                cacheFile = cacheManager.getTrackFile(trackId),
                context = context,
                client = client,
                cacheManager = cacheManager,
                db = db
            )
            session.refCount.set(1)
            activeSessions[trackId] = session
            Log.d(TAG, "[$trackId] Created new session (refCount=1)")
            StreamingService.start(context.applicationContext)
            return session
        }

        @Synchronized
        internal fun releaseSession(trackId: String) {
            val session = activeSessions[trackId] ?: return
            val count = session.refCount.decrementAndGet()
            Log.d(TAG, "[$trackId] Session release called (remaining refCount=$count)")
            if (count <= 0) {
                session.scheduleGracefulCleanup(30_000L) {
                    synchronized(Companion) {
                        if (session.refCount.get() <= 0) {
                            activeSessions.remove(trackId)
                            session.close()
                            Log.d(TAG, "[$trackId] Session disposed after grace period.")
                        }
                    }
                }
            }
        }
    }
}
