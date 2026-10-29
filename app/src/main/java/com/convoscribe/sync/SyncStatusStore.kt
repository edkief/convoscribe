package com.convoscribe.sync

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Settings shows about sync (issue #68): when a run last finished
 * with everything uploaded ([lastSyncedAt]), how many sessions are
 * [waiting], and the [lastError], if the last run had one. [running]
 * is set while a run is in progress.
 */
data class SyncStatus(
    val lastSyncedAt: Long? = null,
    val waiting: Int = 0,
    val lastError: String? = null,
    val running: Boolean = false
)

/** Keeps [SyncStatus] across restarts, in its own preferences file. */
class SyncStatusStore(private val prefs: SharedPreferences) {
    private val _status = MutableStateFlow(load())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun update(transform: (SyncStatus) -> SyncStatus) {
        val next = transform(_status.value)
        prefs.edit().apply {
            if (next.lastSyncedAt == null) remove(KEY_LAST_SYNCED) else putLong(KEY_LAST_SYNCED, next.lastSyncedAt)
            putInt(KEY_WAITING, next.waiting)
            if (next.lastError == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, next.lastError)
        }.apply()
        _status.value = next
    }

    private fun load() = SyncStatus(
        lastSyncedAt = prefs.getLong(KEY_LAST_SYNCED, -1).takeIf { it >= 0 },
        waiting = prefs.getInt(KEY_WAITING, 0),
        lastError = prefs.getString(KEY_LAST_ERROR, null)
    )

    companion object {
        const val FILE_NAME: String = "sync_status"
        private const val KEY_LAST_SYNCED = "lastSyncedAt"
        private const val KEY_WAITING = "waiting"
        private const val KEY_LAST_ERROR = "lastError"
    }
}
