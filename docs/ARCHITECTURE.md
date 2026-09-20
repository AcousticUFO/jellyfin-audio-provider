# Architecture Documentation

## Overview

`Jellyfin Audio Provider` acts as an intermediary bridge between a remote Jellyfin Media Server and local Android audio playback applications (such as Poweramp). It implements Android's `DocumentsProvider` API (part of the Storage Access Framework), exposing remote audio tracks as if they were local files, with on-demand streaming, sparse caching, and rich metadata delivery.

---

## High-Level Architecture

```
+-------------------------------------------------------------+
|                 Android Audio Player (e.g. Poweramp)        |
+-------------------------------------------------------------+
                              |
                     SAF / ContentResolver
                              v
+-------------------------------------------------------------+
|               JellyfinDocumentsProvider                     |
|  - queryRoots()                                             |
|  - queryChildDocuments() (O(1) in-memory folder cache)       |
|  - queryDocument() (Metadata & MediaStore columns)           |
|  - openDocument() (ProxyFileDescriptor)                     |
|  - openDocumentThumbnail()                                  |
+-------------------------------------------------------------+
         |                                           |
         v                                           v
+-----------------------+               +-----------------------+
|   SQLite / Room v5    |               |  ProxyStreamHandler   |
|   (Local Metadata)    |               |  - Header Parser      |
|  - TrackEntity        |               |  - IntervalSet Cache  |
|  - RelativeDir Index  |               |  - OkHttp Range Feeds |
+-----------------------+               +-----------------------+
         ^                                           |
         |                                           v
+-----------------------+               +-----------------------+
|  LibrarySyncService   |               |    LRUCacheManager    |
|  (Foreground Service) |               |  (Sparse Disk Cache)  |
+-----------------------+               +-----------------------+
         |                                           |
         +--------------------+----------------------+
                              |
                     HTTP / TLS 1.2+
                              v
+-------------------------------------------------------------+
|                    Jellyfin Media Server                    |
+-------------------------------------------------------------+
```

---

## 1. Storage Access Framework (`JellyfinDocumentsProvider`)

### Document Identifiers (`DocumentId.kt`)
Every item exposed to the Android operating system requires a unique, persistent string identifier. We employ a structured typed system:

- **Root:** `"root"`
- **Folder:** `"dir:" + Base64UrlSafe(relativeDir)`
  - *Why Base64?* Folder names often contain characters like `+`, `%`, `&`, `[`, `]`, or Unicode scripts. Standard URL-decoding would inadvertently convert `+` into a space (breaking folders like `2+2=5`). URL-safe Base64 eliminates all character collisions.
- **Album:** `"album:" + UUID`
- **Track:** `"track:" + albumId + ":" + trackId + ":" + sizeBytes + ":" + durationMs`

### In-Memory Directory Cache
On large music libraries (10,000+ tracks across thousands of directories), running recursive database queries during a player's library scan causes noticeable lag and IPC bottlenecks.
- `JellyfinDocumentsProvider` compiles an in-memory directory map (`subfolderMap`) upon first access.
- Subsequent `queryChildDocuments` calls resolve immediately in O(1) time.
- The cache is automatically invalidated when `LibrarySyncService` completes a new synchronization run.

### MediaStore & Tag Projection
Poweramp's scanner relies on both standard Android MediaStore columns and custom provider columns:
- `MediaStore.Audio.Media.TRACK`: Formatted as compound track number (`disc * 1000 + track`) when multi-disc albums are detected.
- `track_tag`, `track_number_alt`: Contains the raw track number for clean UI presentation.
- `MediaStore.Audio.Media.DISC_NUMBER`, `disc_total`: Conveys exact disc and volume numbering.
- `MediaStore.Audio.Media.ALBUM`: Pure, unmodified album title from Jellyfin (no artificial edition mangling).

---

## 2. On-Demand Streaming Engine (`ProxyStreamHandler`)

Android's `StorageManager.openProxyFileDescriptor` allows the app to intercept seek and read operations performed by native decoders in real time.

### Dynamic Header Parser (Skipping Giant Vinyl Artwork)
High-resolution FLAC files (24-bit / 96 kHz or 192 kHz) often contain embedded cover art exceeding 10 MB in size.
1. When a track is opened, the first 64 KB are fetched synchronously.
2. The parser inspects the container header:
   - For **FLAC**, it walks metadata blocks (`STREAMINFO`, `VORBIS_COMMENT`, `PICTURE`, `PADDING`).
   - It extracts sample rate, bit depth, and calculates `audioStartOffset` (the exact byte where audio frames start).
3. If `audioStartOffset` exceeds 256 KB (indicating large embedded artwork), the prefetch stream immediately jumps to `audioStartOffset`.
4. Poweramp receives the header from cache (0 ms) and jumps straight to the audio offset without buffer underruns or track skipping.

### Adaptive Buffering & Gap Filling
- **Warmup Buffer:** 2 MB for Hi-Res audio (exceeding 5 seconds of continuous playback buffer) and 512 KB for standard audio.
- **Background Gap Filling:** Once playback reaches the end of the file, `getFirstMissingRange()` detects any unread sections (such as the skipped cover art) and fetches them in the background. Once all bytes from `[0, totalSizeBytes)` are present, the file is promoted to fully cached status.

---

## 3. Storage & Cache Management (`LRUCacheManager`)

- Tracks are stored in the application's private cache directory: `context.cacheDir/audio_cache/<trackId>.dat`.
- Sparse caching is tracked via accompanying metadata: `cached_bytes`, `is_fully_cached`, and `last_accessed_at`.
- When disk usage exceeds the user-defined quota, an asynchronous eviction worker removes the least recently accessed files, while protecting tracks marked as favorites.

---

## 4. Background Synchronization (`LibrarySyncService`)

- Implemented as an Android Foreground Service with the `dataSync` service type.
- Acquires a `PARTIAL_WAKE_LOCK` for the duration of the synchronization.
- Fetches track and album metadata from Jellyfin in paginated batches of 1,000 items.
- Updates an ongoing notification with current progress and informs the user upon completion.
