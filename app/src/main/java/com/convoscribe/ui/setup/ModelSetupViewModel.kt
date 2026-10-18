package com.convoscribe.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.models.ModelBundle
import com.convoscribe.models.ModelDownloadManager
import com.convoscribe.models.ModelDownloadState
import com.convoscribe.models.ModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * State shown by the first-run model download screen. [totalBytes]
 * is fixed at construction (sum of every missing bundle's files, what
 * lands on disk) so the UI can render "About N MB" before the user taps
 * Download. While running, [doneBytes] of [downloadTotalBytes] are the
 * bytes on the wire (the compressed archive for the NPU bundle), so the
 * bar reaches its end exactly when the download does; `0` falls back to
 * [totalBytes]. [waitingForNetwork] while a retry waits for the
 * network; [unpacking] `0f..1f` while an archive is extracted.
 */
data class SetupUiState(
    val totalBytes: Long,
    val doneBytes: Long = 0L,
    val running: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    val downloadTotalBytes: Long = 0L,
    val waitingForNetwork: Boolean = false,
    val unpacking: Float? = null
) {
    /** Share `0f..1f` of the progress bar. */
    val progress: Float
        get() {
            val denominator = (downloadTotalBytes.takeIf { it > 0 } ?: totalBytes).coerceAtLeast(1L)
            return unpacking ?: (doneBytes.toFloat() / denominator)
        }
}

/**
 * First-run model download ViewModel (issue #10). The download runs in
 * the app-scoped [manager], started through [startDownload] (the
 * foreground download service), so it outlives this screen; [state]
 * follows [ModelDownloadManager.state], and reopening the screen shows
 * the live progress of a download already running. After an error,
 * [start] runs again and resumes from what was downloaded.
 */
class ModelSetupViewModel(
    private val manager: ModelDownloadManager,
    private val store: ModelStore,
    private val bundles: List<ModelBundle>,
    private val startDownload: (List<ModelBundle>) -> Unit
) : ViewModel() {

    // The on-screen "About N MB will be downloaded" string reflects
    // what lands on disk, not the compressed archive body the network
    // pushes: closer to "this is how much space you'll free up if you
    // delete it later" than `downloadBytes`.
    private val missingAtStart = store.missing(bundles)
    private val totalBytes = missingAtStart.sumOf { it.totalBytes }
    private val missingIds = missingAtStart.map { it.id }

    /** `true` once [start] found nothing to download. */
    private val nothingToDo = MutableStateFlow(false)

    val state: StateFlow<SetupUiState> = combine(manager.state, nothingToDo, ::toUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, toUiState(manager.state.value, false))

    /**
     * Download every bundle in [ModelStore.missing]. While a download
     * runs this is a no-op (the screen already shows it), so the UI
     * cannot start two.
     */
    fun start() {
        if (manager.isRunning) return
        val toFetch = store.missing(bundles)
        if (toFetch.isEmpty()) {
            // Nothing to do; mark finished so the screen pops itself.
            nothingToDo.value = true
            return
        }
        startDownload(toFetch)
    }

    private fun toUiState(download: ModelDownloadState, nothingToDo: Boolean): SetupUiState {
        val idle = SetupUiState(totalBytes = totalBytes, finished = nothingToDo)
        return when (download) {
            ModelDownloadState.Idle -> idle
            is ModelDownloadState.Running -> idle.copy(
                running = true,
                doneBytes = download.bytesDone,
                downloadTotalBytes = download.bytesTotal,
                waitingForNetwork = download.waiting,
                unpacking = download.unpacking
            )
            is ModelDownloadState.Failed -> idle.copy(error = download.message)
            // Finished only if the download covered this screen's bundles
            // (one started from Settings may have fetched others).
            is ModelDownloadState.Completed ->
                if (download.bundleIds.containsAll(missingIds)) {
                    idle.copy(finished = true, doneBytes = totalBytes)
                } else {
                    idle
                }
        }
    }
}
