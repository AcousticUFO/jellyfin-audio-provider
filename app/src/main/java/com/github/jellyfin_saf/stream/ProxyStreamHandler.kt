package com.github.jellyfin_saf.stream

import android.content.Context
import android.os.PowerManager
import android.os.ProxyFileDescriptorCallback
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response

/**
 * High-performance, glitch-free audio streaming bridge between Jellyfin and Android SAF / Poweramp.
 *
 * Features:
 * - Thread-safe active session sharing: Prevents duplicate download jobs and cache corruption
 *   when Poweramp opens the same track with multiple file descriptors (metadata inspection + playback).
 * - Thread-safe IntervalSet tracking to guarantee 0 sparse-gap / zero-hole decoder corruptions.
 * - Non-blocking positional FileChannel I/O: readChannel and writeChannel are decoupled.
 * - Single-connection continuous background prefetching with 128KB buffer.
 * - Initial 16KB header warm-up in init to eliminate FFmpeg EOF read timeouts over WAN.
 * - Non-blocking on-demand range retrieval for end-of-file metadata probes (RFC 7233 compliant).
 * - Automatic conversion to 100% locally cached track upon complete download.
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
    private var sequentialBytesRead = 0L

    init {
        if (session.totalSizeBytes <= 0) {
            session.probeTotalSize()
        }
        // Adaptive warmup:
        // High-res audio (24-bit 96kHz / 192kHz) consumes 400-800 KB/sec.
        // For hi-res tracks, warm up with 2MB to provide >5 seconds of initial audio buffer.
        // For standard tracks, warm up with 512KB to provide >5 seconds of buffer.
        val warmupBytes = if (session.isHighRes) 2097152L else 524288L
        val warmupOffset = if (session.audioStartOffset > 256 * 1024L) session.audioStartOffset else 0L
        session.waitForOffset(warmupOffset, warmupBytes, 3500L)
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

        // Reset sequential tracking if read jumped to a different offset
        if (offset != lastReadEnd) {
            sequentialBytesRead = 0L
        }

        // 1. If bytes are already downloaded locally, read immediately via lock-free positional I/O
        val available = session.intervals.getAvailableLengthFrom(offset)
        if (available > 0) {
            val toRead = minOf(maxPossible.toLong(), available).toInt()
            val n = readChannel.read(ByteBuffer.wrap(data, 0, toRead), offset)
            val readBytes = if (n > 0) n else 0
            if (readBytes > 0) {
                lastReadEnd = offset + readBytes
                sequentialBytesRead += readBytes
                checkPrefetcherReposition(lastReadEnd)
            }
            return readBytes
        }

        // 2. Data not yet available locally
        val isEndOfFileProbe = totalSize > 1024 * 1024L && offset >= (totalSize - 1024 * 1024L)

        if (isEndOfFileProbe) {
            // Footer probe (ID3v1, APE tags, FLAC seektable): fetch starting directly at requested offset to EOF
            val footerLength = minOf(totalSize - offset, 512 * 1024L)
            session.fetchDirectRange(offset, footerLength)
        } else {
            // Check if current background download job is actively approaching the requested offset
            val prefetchOffset = session.currentPrefetchOffset
            val isNearCurrentStream = (offset >= prefetchOffset - 64 * 1024L) &&
                    (offset <= prefetchOffset + 768 * 1024L)

            if (isNearCurrentStream) {
                // Background prefetcher is downloading this region; wait for bytes to arrive
                val targetBytes = if (session.isHighRes) minOf(maxPossible.toLong(), 65536L) else minOf(maxPossible.toLong(), 16384L)
                session.waitForOffset(offset, targetBytes, 3500L)
            } else {
                // Non-contiguous read (FLAC seekpoint, audio start jump, or user seek).
                // Immediately reposition the background prefetcher to offset!
                Log.d(TAG, "[$trackId] Non-contiguous read at $offset (prefetcher at $prefetchOffset). Repositioning prefetcher!")
                session.startPrefetchStream(offset)
                val fetchLen = if (session.isHighRes) 2097152L else 1048576L
                session.fetchDirectRange(offset, fetchLen)
            }
        }

        // 3. Read whatever has arrived
        var availAfterWait = session.intervals.getAvailableLengthFrom(offset)
        if (availAfterWait <= 0 && (totalSize <= 0 || offset < totalSize)) {
            // Safety fallback: if wait didn't produce bytes, directly fetch range right now
            val fallbackLen = if (session.isHighRes) 1048576L else 262144L
            session.fetchDirectRange(offset, fallbackLen)
            availAfterWait = session.intervals.getAvailableLengthFrom(offset)
        }

        if (availAfterWait > 0) {
            val toRead = minOf(maxPossible.toLong(), availAfterWait).toInt()
            val n = readChannel.read(ByteBuffer.wrap(data, 0, toRead), offset)
            val readBytes = if (n > 0) n else 0
            if (readBytes > 0) {
                lastReadEnd = offset + readBytes
                sequentialBytesRead += readBytes
                checkPrefetcherReposition(lastReadEnd)
            }
            return readBytes
        }

        Log.w(TAG, "[$trackId] onRead timeout or EOF at offset $offset (requested $size bytes, total=$totalSize)")
        return 0
    }

    private fun checkPrefetcherReposition(currentPos: Long) {
        if (session.totalSizeBytes > 0 && currentPos >= session.totalSizeBytes) return
        // Don't reposition if the track is already fully downloaded
        if (session.totalSizeBytes > 0 && session.intervals.contains(0, session.totalSizeBytes)) return

        // Never reposition on end-of-file metadata probes
        if (session.totalSizeBytes > 1024 * 1024L && currentPos >= session.totalSizeBytes - 1024 * 1024L) return

        if (currentPos >= session.audioStartOffset) {
            val prefetchOffset = session.currentPrefetchOffset
            val playbackAheadOfPrefetcher = currentPos > prefetchOffset + 768 * 1024L
            val backwardSeek = currentPos < prefetchOffset - 1024 * 1024L &&
                    session.intervals.getAvailableLengthFrom(currentPos) <= 0
            if (playbackAheadOfPrefetcher || backwardSeek) {
                Log.d(TAG, "[$trackId] Playback at $currentPos, prefetcher at $prefetchOffset, repositioning (ahead=$playbackAheadOfPrefetcher, backward=$backwardSeek)")
                session.startPrefetchStream(currentPos)
            }
        }
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
        private val context: Context,
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
        @Volatile var currentPrefetchOffset: Long = 0L
        @Volatile var streamingError = false
        private var streamingJob: Job? = null
        @Volatile private var currentStreamResponse: Response? = null

        @Volatile var headerInfo: MediaHeaderInfo = MediaHeaderInfo(
            audioStartOffset = 0L,
            isHighRes = totalSizeBytes > 40 * 1024 * 1024L,
            sampleRate = 44100,
            bitsPerSample = 16,
            container = "unknown"
        )

        val isHighRes: Boolean
            get() = headerInfo.isHighRes || totalSizeBytes > 40 * 1024 * 1024L

        val audioStartOffset: Long
            get() = headerInfo.audioStartOffset

        init {
            val isFullyCached = runBlocking { cacheManager.isTrackFullyCached(trackId) }
            if (!isFullyCached && cacheFile.exists()) {
                cacheFile.delete()
            }
            if (!cacheFile.exists()) {
                cacheFile.createNewFile()
            }
            writeRaf = RandomAccessFile(cacheFile, "rw")
            writeChannel = writeRaf.channel

            wakeLock = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JellyfinAudioProvider:Session-$trackId")?.apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L)
                }
            } catch (_: Exception) {
                null
            }

            // Stream session initialized

            // 1. Probe total size if not set
            if (totalSizeBytes <= 0) {
                probeTotalSize()
            }

            // 2. Fetch initial 64 KB synchronously to parse metadata / artwork headers
            val initialHeaderBytes = minOf(65536L, if (totalSizeBytes > 0) totalSizeBytes else 65536L)
            fetchDirectRange(0L, initialHeaderBytes)

            // 3. Parse header to detect embedded artwork size, audio start offset, and audio resolution
            val avail = intervals.getAvailableLengthFrom(0L)
            if (avail >= 4) {
                val readLen = minOf(avail, 65536L).toInt()
                val headerBytes = ByteArray(readLen)
                synchronized(writeLock) {
                    writeRaf.seek(0)
                    writeRaf.readFully(headerBytes)
                }
                headerInfo = parseHeaderInfo(headerBytes, readLen, totalSizeBytes)
                Log.i(TAG, "[$trackId] Parsed header: container=${headerInfo.container}, audioStart=${headerInfo.audioStartOffset}, hiRes=${headerInfo.isHighRes} (${headerInfo.sampleRate}Hz/${headerInfo.bitsPerSample}bit)")
            }

            // 4. Start prefetch stream:
            // If massive artwork detected (> 256 KB, e.g. 14MB vinyl picture), jump prefetcher directly to audio stream!
            if (headerInfo.audioStartOffset > 256 * 1024L && (totalSizeBytes <= 0 || headerInfo.audioStartOffset < totalSizeBytes)) {
                Log.i(TAG, "[$trackId] Massive embedded artwork (${headerInfo.audioStartOffset / 1024} KB). Directing background prefetcher directly to audio stream at ${headerInfo.audioStartOffset}!")
                startPrefetchStream(headerInfo.audioStartOffset)
            } else {
                val startPos = minOf(intervals.getAvailableLengthFrom(0L), 65536L)
                startPrefetchStream(startPos)
            }
        }

        fun waitForOffset(offset: Long, targetBytes: Long, timeoutMs: Long) {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(streamLock) {
                while (!isReleased && !streamingError && System.currentTimeMillis() < deadline) {
                    val available = intervals.getAvailableLengthFrom(offset)
                    val target = if (totalSizeBytes > 0) minOf(totalSizeBytes - offset, targetBytes) else targetBytes
                    if (available >= target) break
                    if (totalSizeBytes > 0 && offset + available >= totalSizeBytes) break
                    try {
                        (streamLock as Object).wait(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        fun waitForWarmup(targetBytes: Long, timeoutMs: Long) {
            waitForOffset(0L, targetBytes, timeoutMs)
            Log.d(TAG, "[$trackId] Warmup finished: ${intervals.getAvailableLengthFrom(0L)} bytes ready")
        }

        @Synchronized
        fun startPrefetchStream(offset: Long) {
            if (isReleased) return

            // Abort previous blocking socket read immediately
            try {
                currentStreamResponse?.close()
            } catch (_: Exception) {}
            currentStreamResponse = null

            streamingJob?.cancel()
            currentPrefetchOffset = offset
            streamingError = false

            streamingJob = scope.launch {
                try {
                    var fetchOffset = offset

                    while (isActive && !isReleased) {
                        val alreadyAvail = intervals.getAvailableLengthFrom(fetchOffset)
                        if (alreadyAvail > 0) {
                            fetchOffset += alreadyAvail
                            if (totalSizeBytes > 0 && fetchOffset >= totalSizeBytes) {
                                if (!intervals.contains(0, totalSizeBytes)) {
                                    val missing = intervals.getFirstMissingRange(totalSizeBytes)
                                    if (missing != null && isActive && !isReleased) {
                                        fetchOffset = missing.first
                                        continue
                                    }
                                }
                                break
                            }
                            continue
                        }

                        Log.d(TAG, "[$trackId] Opening prefetch stream at offset $fetchOffset")
                        val response = client.openAudioByteStream(trackId, fetchOffset)
                        currentStreamResponse = response

                        try {
                            if (!response.isSuccessful && response.code != 206) {
                                Log.w(TAG, "[$trackId] Stream request failed (HTTP ${response.code})")
                                streamingError = true
                                synchronized(streamLock) {
                                    (streamLock as Object).notifyAll()
                                }
                                break
                            }

                            streamingError = false

                            val cr = response.header("Content-Range")
                            val totalFromRange = cr?.substringAfterLast('/')?.toLongOrNull()
                            val cl = response.header("Content-Length")?.toLongOrNull()
                            val serverTotal = totalFromRange ?: (if (fetchOffset == 0L && cl != null && cl > 0) cl else null)
                            if (serverTotal != null && serverTotal > 0 && serverTotal != totalSizeBytes) {
                                Log.d(TAG, "[$trackId] Server authoritative totalSizeBytes: $totalSizeBytes -> $serverTotal")
                                totalSizeBytes = serverTotal
                            }

                            val rawStream = response.body?.byteStream() ?: break
                            val stream = BufferedInputStream(rawStream, 256 * 1024)
                            val buffer = ByteArray(128 * 1024)

                            while (isActive && !isReleased) {
                                val read = stream.read(buffer)
                                if (read <= 0) {
                                    // Server finished sending data for this stream
                                    if (totalSizeBytes <= 0) {
                                        Log.i(TAG, "[$trackId] Server reached EOF at offset $fetchOffset (was totalSizeBytes=$totalSizeBytes)")
                                        totalSizeBytes = fetchOffset
                                    }
                                    if (totalSizeBytes > 0 && intervals.contains(0, totalSizeBytes)) {
                                        markTrackFullyCached()
                                    }
                                    break
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
                                currentPrefetchOffset = fetchOffset

                                if (totalSizeBytes > 0 && intervals.contains(0, totalSizeBytes)) {
                                    markTrackFullyCached()
                                    return@launch
                                }
                            }
                        } finally {
                            try {
                                response.close()
                            } catch (_: Exception) {}
                            // Only clear if it's still OUR response (avoid race with new coroutine)
                            if (currentStreamResponse === response) {
                                currentStreamResponse = null
                            }
                        }

                        if (totalSizeBytes > 0 && fetchOffset >= totalSizeBytes) {
                            if (!intervals.contains(0, totalSizeBytes)) {
                                val missing = intervals.getFirstMissingRange(totalSizeBytes)
                                if (missing != null && isActive && !isReleased) {
                                    Log.d(TAG, "[$trackId] Audio stream reached EOF, backfilling missing gap: ${missing.first}..${missing.second}")
                                    fetchOffset = missing.first
                                    continue
                                }
                            }
                            if (totalSizeBytes > 0 && intervals.contains(0, totalSizeBytes)) {
                                markTrackFullyCached()
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException && !isReleased && isActive) {
                        Log.w(TAG, "[$trackId] Prefetch stream error at offset $offset: ${e.message}", e)
                        streamingError = true
                        synchronized(streamLock) {
                            (streamLock as Object).notifyAll()
                        }
                    }
                } finally {
                    // Don't close currentStreamResponse here — it may belong to a new coroutine.
                    // Each response is already closed by its inner finally block above.
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
                    Log.d(TAG, "[$trackId] Single-shot direct range fetch $start..$endInclusive")
                    client.openAudioByteStream(trackId, start, endInclusive).use { response ->
                        if (!response.isSuccessful && response.code != 206) {
                            Log.w(TAG, "[$trackId] fetchDirectRange HTTP ${response.code} ($start..$endInclusive)")
                            if (response.code == 416) {
                                val cr = response.header("Content-Range")
                                val total = cr?.substringAfterLast('/')?.toLongOrNull()
                                if (total != null && total > 0) {
                                    totalSizeBytes = total
                                } else if (totalSizeBytes <= 0 || start < totalSizeBytes) {
                                    totalSizeBytes = start
                                }
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
                        Log.d(TAG, "[$trackId] Direct range complete: $start to $current")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[$trackId] fetchDirectRange failed ($start-$endInclusive): ${e.message}")
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

        private fun markTrackFullyCached() {
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
                currentStreamResponse?.close()
            } catch (_: Exception) {}
            currentStreamResponse = null

            streamingJob?.cancel()
            scope.cancel()

            synchronized(streamLock) {
                (streamLock as Object).notifyAll()
            }

            try {
                writeChannel.close()
                writeRaf.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session write channel", e)
            } finally {
                // Session stopped
            }
        }
    }

    companion object {
        private const val TAG = "ProxyStreamHandler"
        private val activeSessions = ConcurrentHashMap<String, TrackSession>()

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
            return session
        }

        @Synchronized
        internal fun releaseSession(trackId: String) {
            val session = activeSessions[trackId] ?: return
            val count = session.refCount.decrementAndGet()
            Log.d(TAG, "[$trackId] Session release called (remaining refCount=$count)")
            if (count <= 0) {
                activeSessions.remove(trackId)
                session.close()
            }
        }

        data class MediaHeaderInfo(
            val audioStartOffset: Long,
            val isHighRes: Boolean,
            val sampleRate: Int,
            val bitsPerSample: Int,
            val container: String
        )

        internal fun parseHeaderInfo(data: ByteArray, length: Int, totalSizeBytes: Long): MediaHeaderInfo {
            if (length >= 4 && data[0] == 0x66.toByte() && data[1] == 0x4C.toByte() && data[2] == 0x61.toByte() && data[3] == 0x43.toByte()) {
                // FLAC container ("fLaC")
                var pos = 4
                var sampleRate = 44100
                var bitsPerSample = 16
                var audioStart = 4L
                while (pos + 4 <= length) {
                    val hdr = data[pos].toInt() and 0xFF
                    val isLast = (hdr and 0x80) != 0
                    val blockType = hdr and 0x7F
                    val blockLen = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                            ((data[pos + 2].toInt() and 0xFF) shl 8) or
                            (data[pos + 3].toInt() and 0xFF)

                    if (blockType == 0 && pos + 4 + minOf(blockLen, 34) <= length) {
                        // STREAMINFO block:
                        // Byte 10: sample rate high [19..12]
                        // Byte 11: sample rate mid [11..4]
                        // Byte 12: sample rate low [3..0] (top 4 bits), bits per sample top bit
                        // Byte 13: bits per sample [3..0] (top 4 bits)
                        if (pos + 4 + 18 <= length) {
                            val b10 = data[pos + 4 + 10].toInt() and 0xFF
                            val b11 = data[pos + 4 + 11].toInt() and 0xFF
                            val b12 = data[pos + 4 + 12].toInt() and 0xFF
                            val b13 = data[pos + 4 + 13].toInt() and 0xFF
                            sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                            bitsPerSample = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                        }
                    }

                    pos += 4 + blockLen
                    audioStart = pos.toLong()
                    if (isLast) break
                }
                val isHiRes = sampleRate >= 88200 || bitsPerSample > 16 || totalSizeBytes > 40 * 1024 * 1024L
                return MediaHeaderInfo(
                    audioStartOffset = audioStart,
                    isHighRes = isHiRes,
                    sampleRate = sampleRate,
                    bitsPerSample = bitsPerSample,
                    container = "flac"
                )
            } else if (length >= 10 && data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte()) {
                // ID3v2 tag ("ID3")
                val flags = data[5].toInt() and 0xFF
                val tagSize = ((data[6].toInt() and 0x7F) shl 21) or
                        ((data[7].toInt() and 0x7F) shl 14) or
                        ((data[8].toInt() and 0x7F) shl 7) or
                        (data[9].toInt() and 0x7F)
                val hasFooter = (flags and 0x10) != 0
                val audioStart = 10L + tagSize + (if (hasFooter) 10L else 0L)
                val isHiRes = totalSizeBytes > 50 * 1024 * 1024L
                return MediaHeaderInfo(
                    audioStartOffset = audioStart,
                    isHighRes = isHiRes,
                    sampleRate = 44100,
                    bitsPerSample = 16,
                    container = "mp3"
                )
            }

            val isHiRes = totalSizeBytes > 40 * 1024 * 1024L
            return MediaHeaderInfo(
                audioStartOffset = 0L,
                isHighRes = isHiRes,
                sampleRate = 44100,
                bitsPerSample = 16,
                container = "unknown"
            )
        }
    }
}
