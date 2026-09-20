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
 * Implements a robust VFS read-through sparse cache architecture:
 * - Decoupled lock-free positional FileChannel reads for zero-latency cache hits.
 * - Single continuous background streaming worker with transparent HTTP Range auto-reconnection.
 * - Non-blocking on-demand metadata footer retrieval without stream interruption.
 * - Session keep-alive grace period preventing premature cache deletion during player probe cycles.
 * - Strict POSIX EOF compliance: never returns 0 when offset < totalSizeBytes.
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

    private val readRaf: RandomAccessFile = RandomAccessFile(session.cacheFile, "r")
    private val readChannel: FileChannel = readRaf.channel

    @Volatile
    private var isReleased = false
    private var lastReadEnd = -1L

    init {
        if (session.totalSizeBytes <= 0) {
            session.probeTotalSize()
        }
        // Wait up to 5 seconds for initial header bytes (64 KB) to be ready on disk
        session.waitForBytes(0L, minBytes = 65536L, timeoutMs = 5000L)
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

        val maxPossible = if (totalSize > 0) minOf(size.toLong(), totalSize - offset).toInt() else size
        if (maxPossible <= 0) return 0

        // In POSIX regular file I/O, read() never returns a short read unless EOF is reached.
        // Returning fewer bytes than requested causes native audio demuxers (like FFmpeg in Poweramp)
        // to parse truncated packet headers, resulting in "invalid residual" / premature EOF decode failures.
        val minWaitBytes = maxPossible.toLong()

        // 1. Fast path: data is already available locally on disk
        val available = session.intervals.getAvailableLengthFrom(offset)
        if (available >= minWaitBytes || (totalSize > 0 && offset + available >= totalSize)) {
            val toRead = minOf(maxPossible.toLong(), available).toInt()
            val n = readChannel.read(ByteBuffer.wrap(data, 0, toRead), offset)
            val readBytes = if (n > 0) n else 0
            if (readBytes > 0) {
                lastReadEnd = offset + readBytes
            }
            return readBytes
        }

        // 2. Cache miss: check if this is an end-of-file metadata probe (ID3v1, APE, FLAC seektable)
        val isEndOfFileProbe = totalSize > 512 * 1024L && offset >= (totalSize - 512 * 1024L)
        if (isEndOfFileProbe) {
            val footerLen = minOf(totalSize - offset, 256 * 1024L)
            session.fetchDirectRange(offset, footerLen)
        } else {
            // Check if download stream needs to jump to this seek position
            val currentDl = session.currentDownloadOffset
            val isSeek = offset < currentDl - 128 * 1024L || offset > currentDl + 2 * 1024 * 1024L
            if (isSeek) {
                Log.d(TAG, "[$trackId] Seek jump: read at $offset (downloader at $currentDl). Repositioning stream.")
                session.startDownloadStream(offset)
            }
            // Wait for sufficient bytes to arrive at offset
            session.waitForBytes(offset, minBytes = minWaitBytes, timeoutMs = 15000L)
        }

        // 3. Fallback: if stream wait was insufficient or timed out, fetch via direct range
        var availAfterWait = session.intervals.getAvailableLengthFrom(offset)
        if (availAfterWait < minWaitBytes && (totalSize <= 0 || offset + availAfterWait < totalSize)) {
            val needed = if (totalSize > 0) minOf(minWaitBytes, totalSize - offset) else minWaitBytes
            val fetchStart = offset + availAfterWait
            val fetchLen = needed - availAfterWait
            Log.d(TAG, "[$trackId] Stream wait underrun ($availAfterWait < $needed at $offset). Triggering direct range fetch at $fetchStart (len $fetchLen).")
            session.fetchDirectRange(fetchStart, fetchLen)
            availAfterWait = session.intervals.getAvailableLengthFrom(offset)
        }

        // 4. Read available bytes after wait / fallback fetch
        if (availAfterWait >= minWaitBytes || (totalSize > 0 && offset + availAfterWait >= totalSize)) {
            val toRead = minOf(maxPossible.toLong(), availAfterWait).toInt()
            val n = readChannel.read(ByteBuffer.wrap(data, 0, toRead), offset)
            val readBytes = if (n > 0) n else 0
            if (readBytes > 0) {
                lastReadEnd = offset + readBytes
            }
            return readBytes
        }

        // 5. Underrun: never return 0 when offset < totalSize to avoid premature EOF cutoffs
        if (totalSize > 0 && offset >= totalSize) {
            return 0
        }

        Log.w(TAG, "[$trackId] Read underrun at offset $offset (requested $size bytes, avail=$availAfterWait, total=$totalSize)")
        throw ErrnoException("onRead", OsConstants.EAGAIN)
    }

    override fun onRelease() {
        if (isReleased) return
        isReleased = true
        Log.d(TAG, "[$trackId] Proxy handle released.")

        try {
            readChannel.close()
            readRaf.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stream read channel", e)
        }

        releaseSession(trackId)
    }

    /**
     * Internal streaming session managing background downloads, interval tracking,
     * and file channel writes for a specific track. Shared among multiple concurrent
     * file descriptors opened by Poweramp for the same track.
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
        val writeLock = Any()
        val directRangeLock = Any()
        val refCount = AtomicInteger(0)

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val writeRaf: RandomAccessFile
        private val writeChannel: FileChannel

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
            }
            writeRaf = RandomAccessFile(cacheFile, "rw")
            writeChannel = writeRaf.channel

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

        fun waitForBytes(offset: Long, minBytes: Long = 1L, timeoutMs: Long = 15000L): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(streamLock) {
                while (!isReleased && System.currentTimeMillis() < deadline) {
                    val available = intervals.getAvailableLengthFrom(offset)
                    val target = if (totalSizeBytes > 0) minOf(totalSizeBytes - offset, minBytes) else minBytes
                    if (available >= target) return true
                    if (totalSizeBytes > 0 && offset + available >= totalSizeBytes) return true
                    if (streamingError) return false
                    try {
                        (streamLock as Object).wait(100)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
                return intervals.getAvailableLengthFrom(offset) >= minBytes
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
                var fetchOffset = offset
                var consecutiveErrors = 0
                val maxRetries = 5

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

                            synchronized(writeLock) {
                                val bb = ByteBuffer.wrap(buffer, 0, read)
                                var pos = fetchOffset
                                while (bb.hasRemaining()) {
                                    val written = writeChannel.write(bb, pos)
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
            val endInclusive = if (totalSizeBytes > 0) {
                minOf(totalSizeBytes - 1L, start + requestedLength - 1L)
            } else {
                start + requestedLength - 1L
            }
            if (start > endInclusive) return

            val needed = minOf(requestedLength, if (totalSizeBytes > 0) totalSizeBytes - start else requestedLength)
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

                            synchronized(writeLock) {
                                val bb = ByteBuffer.wrap(buf, 0, read)
                                var pos = current
                                while (bb.hasRemaining()) {
                                    val written = writeChannel.write(bb, pos)
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

            try {
                writeChannel.close()
                writeRaf.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session write channel", e)
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
                val appContext = session.context.applicationContext
                session.scheduleGracefulCleanup(30_000L) {
                    synchronized(Companion) {
                        if (session.refCount.get() <= 0) {
                            activeSessions.remove(trackId)
                            session.close()
                            Log.d(TAG, "[$trackId] Session disposed after grace period.")
                            if (activeSessions.isEmpty()) {
                                StreamingService.stop(appContext)
                            }
                        }
                    }
                }
            }
        }
    }
}
