package com.github.jellyfin_saf.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.jellyfin_saf.api.JellyfinClient
import com.github.jellyfin_saf.sync.LibrarySyncManager
import com.github.jellyfin_saf.sync.LibrarySyncService
import com.github.jellyfin_saf.ui.theme.JellyfinBlue
import kotlinx.coroutines.launch

/**
 * Server connection and library synchronization screen.
 *
 * Authentication uses a standard coroutine tied to the composable scope (fast, interactive).
 * Library sync is delegated to [LibrarySyncService] so it survives app switching.
 * Sync progress is observed reactively from [LibrarySyncManager.state].
 */
@Composable
fun ConnectionScreen(client: JellyfinClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = client.prefs

    var serverUrl by remember { mutableStateOf(prefs.serverUrl.ifEmpty { "" }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var isConnecting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(prefs.isAuthenticated()) }

    // Observe sync state from the foreground service
    val syncState by LibrarySyncManager.state.collectAsState()
    val isSyncing = syncState is LibrarySyncManager.SyncState.Syncing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // -- Connection status banner --
        ConnectionStatusBanner(isConnected)

        // -- Server URL --
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://jellyfin.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !isConnecting && !isSyncing
        )

        // -- Username --
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting && !isSyncing
        )

        // -- Password --
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting && !isSyncing
        )

        // -- Status message --
        AnimatedVisibility(
            visible = statusMessage != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = statusMessage ?: "",
                color = if (isError) MaterialTheme.colorScheme.error else JellyfinBlue,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // -- Connect button --
        Button(
            onClick = {
                isConnecting = true
                statusMessage = "Connecting..."
                isError = false
                scope.launch {
                    val result = client.authenticate(serverUrl, username, password)
                    isConnecting = false
                    result.fold(
                        onSuccess = {
                            isConnected = true
                            isError = false
                            statusMessage = "Connected successfully."
                            password = "" // Clear password from memory
                        },
                        onFailure = {
                            isError = true
                            statusMessage = it.message ?: "Authentication failed"
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting && !isSyncing && serverUrl.isNotBlank() && username.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = JellyfinBlue)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text("Connect")
        }

        // -- Sync and logout (visible when connected) --
        AnimatedVisibility(
            visible = isConnected,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Sync progress card
                SyncProgressCard(syncState)

                // Sync button
                Button(
                    onClick = { LibrarySyncService.startSync(context) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting && !isSyncing,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSyncing) "Syncing..." else "Sync Full Library")
                }

                // Logout button
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            client.logout()
                            isConnected = false
                            statusMessage = "Logged out."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    enabled = !isConnecting && !isSyncing
                ) {
                    Text("Log Out")
                }
            }
        }
    }
}

/** Displays a compact banner indicating the current connection state. */
@Composable
private fun ConnectionStatusBanner(isConnected: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Outlined.CheckCircle else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = if (isConnected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = if (isConnected) "Connected" else "Not Connected",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (isConnected)
                        "Credentials stored securely"
                    else
                        "Enter your server details below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Shows real-time sync progress from [LibrarySyncManager]. */
@Composable
private fun SyncProgressCard(state: LibrarySyncManager.SyncState) {
    when (state) {
        is LibrarySyncManager.SyncState.Syncing -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Downloading metadata...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.totalCount > 0) {
                        LinearProgressIndicator(
                            progress = { state.currentCount.toFloat() / state.totalCount },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = JellyfinBlue
                        )
                        Text(
                            text = "${state.currentCount} / ${state.totalCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = JellyfinBlue
                        )
                    }
                }
            }
        }
        is LibrarySyncManager.SyncState.Completed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${state.totalTracks} tracks across ${state.totalAlbums} albums",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        is LibrarySyncManager.SyncState.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Sync error: ${state.message}",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        is LibrarySyncManager.SyncState.Idle -> {
            // Nothing to display
        }
    }
}
