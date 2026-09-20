package com.github.jellyfin_saf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.jellyfin_saf.cache.LRUCacheManager
import com.github.jellyfin_saf.security.SecurePreferences
import com.github.jellyfin_saf.ui.theme.JellyfinBlue
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Cache management screen showing disk usage, LRU quota slider, and manual purge controls.
 */
@Composable
fun CacheScreen(cacheManager: LRUCacheManager, prefs: SecurePreferences) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<LRUCacheManager.CacheStats?>(null) }
    var maxLimitMb by remember { mutableFloatStateOf(prefs.maxCacheMb.toFloat()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshStats() {
        scope.launch { stats = cacheManager.getStats() }
    }

    LaunchedEffect(Unit) { refreshStats() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Storage",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // -- Disk usage card --
        val currentStats = stats
        if (currentStats != null) {
            val usedMb = currentStats.totalBytes / (1024 * 1024)
            val limitMb = currentStats.maxLimitBytes / (1024 * 1024)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Disk Usage",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinearProgressIndicator(
                        progress = { currentStats.usedPercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = JellyfinBlue
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$usedMb MB used", style = MaterialTheme.typography.bodySmall)
                        Text("$limitMb MB limit", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Cached songs: ${currentStats.cachedSongsCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Album art: ${currentStats.albumArtBytes / (1024 * 1024)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // -- Cache limit slider --
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Cache Limit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "When storage exceeds this limit, the least recently played tracks are automatically removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = maxLimitMb,
                    onValueChange = {
                        maxLimitMb = it
                        prefs.maxCacheMb = it.toLong()
                    },
                    valueRange = 512f..16384f,
                    steps = 15
                )
                Text(
                    text = "${"%.1f".format(maxLimitMb / 1024f)} GB (${maxLimitMb.roundToInt()} MB)",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // -- Status message --
        if (message != null) {
            Text(
                text = message ?: "",
                color = JellyfinBlue,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // -- Action buttons --
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val pruned = cacheManager.enforceCacheQuota()
                        message = "Pruned $pruned tracks."
                        refreshStats()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.CleaningServices, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Prune")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        cacheManager.clearCache(includeFavorites = false)
                        message = "Cache cleared (favorites preserved)."
                        refreshStats()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear All")
            }
        }
    }
}
