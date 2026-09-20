package com.github.jellyfin_saf.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized reactive state holder for library synchronization.
 *
 * Shared between [LibrarySyncService] (which produces state updates) and the UI
 * layer (which observes them). Using a singleton StateFlow ensures the UI always
 * reflects the latest sync progress, even if the user navigates away and returns.
 */
object LibrarySyncManager {

    /** Represents the current phase of a library sync operation. */
    sealed class SyncState {
        /** No sync is running. */
        data object Idle : SyncState()

        /** Sync is actively downloading metadata from the server. */
        data class Syncing(
            val currentCount: Int,
            val totalCount: Int,
            val message: String
        ) : SyncState()

        /** Sync completed successfully. */
        data class Completed(
            val totalTracks: Int,
            val totalAlbums: Int
        ) : SyncState()

        /** Sync encountered an unrecoverable error. */
        data class Error(val message: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** Observable sync state for UI consumption. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Whether a sync operation is currently in progress. */
    val isSyncing: Boolean
        get() = _state.value is SyncState.Syncing

    internal fun updateState(newState: SyncState) {
        _state.value = newState
    }

    internal fun reset() {
        _state.value = SyncState.Idle
    }
}
