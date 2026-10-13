# Jellyfin Audio Provider for Android

A high-performance Android [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) (`DocumentsProvider`) that exposes your [Jellyfin](https://jellyfin.org) music library as native folders to local media players like [Poweramp](https://powerampapp.com), Symfonium, USB Audio Player PRO, and other SAF-compatible players.

---

## Features

### Native Storage Access Framework (SAF) & Server Hierarchy
- Appears directly in the Android system file picker as a native document provider (`Jellyfin Music`).
- Dynamically mirrors the physical directory structure of your Jellyfin server at arbitrary depth (e.g. `Artist/Album/Track`, `Artist/Grouping/Year - Album [Edition]/Disc-Track - Title`, or any custom organization).
- Multiple editions of the same album (e.g. Standard vs. Deluxe / Multi-CD) are cleanly separated into distinct folders with independent cover art.

### High-Resolution Audio & Dynamic Header Inspection
- Native streaming support for FLAC (16-bit, 24-bit, 96 kHz, 192 kHz), ALAC, MP3, AAC, OGG Vorbis, and Opus.
- **Dynamic Header Parsing:** Inspects the first 64 KB of FLAC/ID3 streams to calculate the exact audio data offset. For files with giant embedded artwork (such as 14 MB vinyl scans), the prefetch engine immediately seeks past the artwork directly to the audio frames, preventing decoder buffer underruns and track skipping.

### Adaptive HTTP Range Streaming & Sparse Caching
- Audio tracks stream on-demand using standard HTTP `Range` requests through a local `ProxyFileDescriptor`.
- Continuous background filling downloads remaining bytes without interrupting playback.
- Once a track is fully downloaded, subsequent playbacks are served directly from disk with zero network overhead.

### Background Playback Continuity & OS Anti-Freezer
- Dedicated foreground playback service (`StreamingService`) running with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` and `START_STICKY`.
- Indefinite CPU partial wake lock prevents Samsung One UI App Freezer (MARs) and Android 14+ cgroup freezes during app switches, screen lock, and system multitasking.
- Stream proxy threads run with elevated `Process.THREAD_PRIORITY_AUDIO` (`-16`) scheduling priority, guaranteeing micro-cut-free Binder reads.

### Synchronized Lyrics (LRC) & Real-Time Scrolling
- **Full LRC Conversion:** Converts 100ns Jellyfin server ticks into standard LRC timestamp tags (`[mm:ss.xx]Text`) in real-time.
- **Native Poweramp Integration:** Delivers synchronized lyrics via `lyrics_synced` and `lyrics` cursor columns, and declares the Poweramp API `FLAG_HAS_LYRICS` (`0x0004`) flag in `__flags` to activate Poweramp's scrolling lyrics UI.
- **Local Disk Caching & Background Pre-caching:** Fetched lyrics are persisted as `.lrc` files in internal storage for instant 0 ms offline access. As soon as a track begins streaming, lyrics are pre-fetched asynchronously in the background.

### Background Foreground Service Sync & Reconciliation
- Synchronizes your entire library metadata via a dedicated Android foreground service (`dataSync`).
- **Mark-and-Sweep Reconciliation:** Automatically purges obsolete, moved, or retagged tracks from the local SQLite Room index and deletes orphaned disk cache files.
- Persists across app switches, screen lock, and memory pressure with a real-time progress notification.
- CPU partial wake lock prevents Android from suspending sync operations on large music collections (tested on 14,000+ tracks).

### Poweramp Multi-Disc & Tag Integration
- Full compliance with Poweramp's internal SAF metadata requirements:
  - `track_tag` and `track_number_alt` for clean track display.
  - MediaStore compound track numbering (`disc * 1000 + track`) for accurate multi-disc sorting.
  - Multi-variant disc columns (`disc_number`, `disc_total`, `tpos`, `part_of_a_set`).
  - Unmodified audio tags (`ALBUM`, `ARTIST`, `COMPOSER`, `GENRE`, `YEAR`).
  - Native synchronized (`lyrics_synced`) and unsynchronized (`lyrics`) metadata delivery.

### Configurable LRU Cache Manager
- Set storage quotas (e.g. 2 GB, 5 GB, 10 GB) with automatic Least-Recently-Used eviction.
- Favorite tracks can be prioritized to prevent automatic eviction.
- Cache statistics, clear options, and individual track management.

### Modern Jetpack Compose Interface
- Built with Jetpack Compose Material 3.
- Focused three-tab navigation:
  - **Server:** Connection configuration, server status, and background sync trigger.
  - **Storage:** Cache usage breakdown and quota settings.
  - **Guide:** Step-by-step setup instructions for Poweramp.

---

## Architecture Overview

```
com.github.jellyfin_saf/
├── api/              # Jellyfin REST API client, DTOs, and metadata parsers
│   ├── JellyfinClient.kt
│   └── model/JellyfinModels.kt
├── cache/            # LRU disk cache manager with thread-safe file tracking
│   └── LRUCacheManager.kt
├── db/               # Room database (SQLite) for offline metadata index
│   ├── AppDatabase.kt
│   ├── TrackDao.kt
│   └── TrackEntity.kt
├── provider/         # Storage Access Framework DocumentsProvider implementation
│   ├── DocumentId.kt
│   └── JellyfinDocumentsProvider.kt
├── security/         # EncryptedSharedPreferences and path traversal validators
│   ├── InputValidator.kt
│   └── SecurePreferences.kt
├── stream/           # HTTP Range streaming engine, proxy, and playback service
│   ├── IntervalSet.kt
│   ├── ProxyStreamHandler.kt
│   └── StreamingService.kt
├── sync/             # Foreground synchronization service and state manager
│   ├── LibrarySyncManager.kt
│   └── LibrarySyncService.kt
└── ui/               # Jetpack Compose UI screens, theme, and navigation
    ├── MainActivity.kt
    ├── screens/
    │   ├── CacheScreen.kt
    │   ├── ConnectionScreen.kt
    │   └── HelpScreen.kt
    └── theme/
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

For an in-depth architectural breakdown, refer to [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Getting Started

### Prerequisites
- Android device or emulator running Android 8.0 (API 26) or higher.
- A running Jellyfin server (version 10.8.0 or newer).
- JDK 17+ and Android SDK 35 (to build from source).

### Download Prebuilt APK
Prebuilt release APKs are available on the [Releases](https://github.com/AcousticUFO/jellyfin-audio-provider/releases) page:
- `app-release.apk` (Production signed release build with R8 optimization).

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/AcousticUFO/jellyfin-audio-provider.git
   cd jellyfin-audio-provider
   ```

2. Configure the Android SDK location:
   ```bash
   cp local.properties.example local.properties
   # Edit local.properties and set sdk.dir to your Android SDK path
   ```

3. Build the release APK:
   ```bash
   ./gradlew assembleRelease
   ```
   The generated APK will be located at `app/build/outputs/apk/release/app-release.apk`.

4. Install the APK to your device:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

---

## Setup Guide

### 1. Connect to Jellyfin
1. Launch **Jellyfin Audio Provider**.
2. Enter your Jellyfin server address:
   - For remote servers: `https://jellyfin.example.com`
   - For local networks: `http://jellyfin.local:8096` (or your local IP address)
3. Enter your Jellyfin username and password, then tap **Connect**.
4. Once connected, tap **Sync Full Library**. A system notification will display the download progress. You may minimize the app or turn off the screen; synchronization will continue in the background.

### 2. Configure Poweramp
1. Open **Poweramp** and navigate to **Settings** > **Library** > **Music Folders**.
2. Tap **Add Folder** (this opens Android's Storage Access Framework file picker).
3. Open the navigation drawer on the left and select **Jellyfin Music**.
4. Navigate into the library root and tap **Use this folder**.
5. Grant access when prompted by the system.
6. Poweramp will scan the collection and populate your artists, albums, tracks, and artwork.

### 3. Recommended Poweramp Buffer Settings (Zero Micro-cuts)
For completely stutter-free and micro-cut-free playback over Android SAF during heavy UI animations (e.g. returning to the home launcher or opening the Recents app switcher):
1. In Poweramp, open **Settings** > **Audio** > **Output**.
2. Select your active output method (e.g. *OpenSL ES Output* or *AudioTrack Output*) and tap its settings gear icon.
3. Configure the following parameters:
   - **Buffer size:** `100 ms`
   - **Number of buffers:** `5` (yielding ~500–600 ms total buffer headroom)
   - **Audio Thread Priority:** `+2`

For detailed recommendations on Poweramp scanner settings and album artwork, see [docs/POWERAMP_CONFIGURATION.md](docs/POWERAMP_CONFIGURATION.md).

---

## Security Model

The application follows defense-in-depth principles:

### Credential Protection
- User credentials and Jellyfin authentication tokens are encrypted using **Android Keystore-backed AES-256-GCM** via `EncryptedSharedPreferences`.
- Raw passwords in memory are cleared immediately following token acquisition.

### Input Validation & Path Traversal Prevention
- All document IDs (Root, Folder, Album, Track) undergo strict validation and UUID format verification.
- Folder paths use URL-safe Base64 encoding and strictly reject null bytes (`\0`) and traversal sequences (`..`).
- File system operations enforce canonical sandbox containment (`file.canonicalPath.startsWith(cacheDir.canonicalPath)`).

### Network Hardening
- Permits cleartext HTTP for local self-hosted instances on private subnets (RFC 1918 / LAN) without requiring reverse proxy setups.
- **HTTPS with TLS 1.2+ is strongly recommended** when streaming over public networks or the Internet.

### Memory & Streaming Boundary Defenses
- Position-based `FileChannel` I/O enforces strict non-negative offset boundaries (`offset >= 0`, `size > 0`), preventing buffer underflow/overflow crashes.
- Concurrent read/prefetch ranges are tracked via thread-safe `@Synchronized` interval sets.

### Application Hardening
- Android cloud and device backup of database caches and tokens is explicitly blocked via `backup_rules.xml` and `data_extraction_rules.xml`.
- Background services and content providers enforce non-exportable configurations (`android:exported="false"`).
- The `DocumentsProvider` requires the platform-level `android.permission.MANAGE_DOCUMENTS` permission.

---

## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
