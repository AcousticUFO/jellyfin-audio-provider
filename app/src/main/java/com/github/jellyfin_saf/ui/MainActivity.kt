package com.github.jellyfin_saf.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.jellyfin_saf.api.JellyfinClient
import com.github.jellyfin_saf.cache.LRUCacheManager
import com.github.jellyfin_saf.ui.screens.CacheScreen
import com.github.jellyfin_saf.ui.screens.ConnectionScreen
import com.github.jellyfin_saf.ui.screens.HelpScreen
import com.github.jellyfin_saf.ui.theme.JellyfinAudioProviderTheme

/**
 * Single-activity entry point for the application.
 *
 * Hosts a three-tab Compose scaffold:
 *   0 - Server: connection and library sync
 *   1 - Storage: cache management and disk usage
 *   2 - Guide: setup instructions for Poweramp integration
 *
 * On Android 13+ (TIRAMISU), requests the POST_NOTIFICATIONS runtime permission
 * so that foreground services (audio streaming, library sync) can display
 * their mandatory notifications.
 */
class MainActivity : ComponentActivity() {

    // Launcher for the POST_NOTIFICATIONS runtime permission dialog (Android 13+).
    // The result callback is intentionally empty: notifications are best-effort.
    // If denied, foreground services still work but their notifications are hidden.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val client = JellyfinClient(this)
        val cacheManager = LRUCacheManager(this)

        setContent {
            JellyfinAudioProviderTheme {
                AppShell(client, cacheManager)
            }
        }
    }

    /**
     * On Android 13+ (API 33), POST_NOTIFICATIONS is a runtime permission.
     * Without it, foreground service notifications are silently suppressed.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** Top-level scaffold with a bottom navigation bar. */
@Composable
private fun AppShell(client: JellyfinClient, cacheManager: LRUCacheManager) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    val tabs = remember {
        listOf(
            TabItem("Server", Icons.Outlined.SyncAlt),
            TabItem("Storage", Icons.Outlined.Folder),
            TabItem("Guide", Icons.Outlined.Info)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = {
                            Text(
                                text = tab.label,
                                fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = selectedIndex,
            modifier = Modifier.padding(innerPadding),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_transition"
        ) { page ->
            when (page) {
                0 -> ConnectionScreen(client)
                1 -> CacheScreen(cacheManager, client.prefs)
                2 -> HelpScreen()
            }
        }
    }
}

/** Lightweight data holder for a navigation tab. */
private data class TabItem(val label: String, val icon: ImageVector)
