# Poweramp Configuration Guide

This guide provides step-by-step instructions for integrating and optimizing [Poweramp Music Player](https://powerampapp.com) with the Jellyfin Audio Provider.

---

## 1. Adding the Jellyfin Music Folder

1. Open **Poweramp**.
2. Navigate to **Settings** > **Library** > **Music Folders**.
3. Tap **Add Folder**. Android's system document picker will open.
4. Tap the **hamburger menu (three horizontal lines)** in the top-left corner to open the side drawer.
5. Select **Jellyfin Music** from the list of storage providers.
6. At the bottom of the screen, tap **Use this folder**.
7. When the system prompt appears, confirm by tapping **Allow**.
8. Poweramp will begin scanning the metadata exposed by the provider.

---

## 2. Navigating Multiple Editions & Folder Hierarchy

If your Jellyfin library contains multiple editions of the same album (e.g. `Album` and `Album [Deluxe Edition]` or `Vinyl Edition`), they will share the same `ALBUM` tag to maintain library integrity.

To browse different editions distinctly:
1. In Poweramp's main library view, tap the **Folders** or **Folder Hierarchy** category.
2. Navigate into your artist and album directories.
3. Each edition is displayed as its own distinct folder with its corresponding tracks and embedded cover art.

---

## 3. Recommended Poweramp Settings for Network Streaming

Because audio tracks are streamed on-demand over HTTP, configuring optimal buffer sizes in Poweramp ensures smooth, stutter-free playback even on fluctuating mobile connections:

### Audio Buffer
1. Go to **Settings** > **Audio** > **Audio Buffer**.
2. Set the buffer size to **Huge (+750ms)** or **Maximum (+1000ms)**.
3. This provides extra headroom during track changes and high-bitrate FLAC streaming.

### Artwork Cache
1. Go to **Settings** > **Album Art**.
2. Ensure **Prefer In-Folder Images** and **Prefer Embedded Art** are enabled.
3. The provider automatically serves full-resolution thumbnails for albums and individual tracks.

---

## 4. Troubleshooting

### Scanner Shows Fewer Tracks than Jellyfin
1. Open the **Jellyfin Audio Provider** app.
2. Check the **Server** tab to verify that the library synchronization has reached 100%.
3. In Poweramp, go to **Settings** > **Library** and tap **Full Rescan**.

### Audio Pauses on Screen Lock
1. Ensure battery optimization is disabled for both **Jellyfin Audio Provider** and **Poweramp**.
2. On Android: **Settings** > **Apps** > **Jellyfin Audio Provider** > **Battery** > select **Unrestricted**.

### "Failed to play" or File Errors
- Check that your Jellyfin server is accessible and that the credentials entered in the provider app have not expired.
- If using an internal network address (e.g. `http://jellyfin.local:8096` or private LAN IP), ensure your device is connected to the same local Wi-Fi network or connected via VPN.
