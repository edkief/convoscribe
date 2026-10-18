package com.convoscribe.models

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's model download, observed by the setup screen and the
 * download service's notification (issue #10). [bundleIds] name the
 * bundles of the download a state belongs to.
 */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    /**
     * [bytesDone] of [bytesTotal] downloaded (compressed bytes for an
     * archive), [currentFile] being fetched. [waiting] while a retry
     * waits for the network; [unpacking] `0f..1f` while an archive is
     * extracted.
     */
    data class Running(
        val bundleIds: List<String>,
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentFile: String? = null,
        val waiting: Boolean = false,
        val unpacking: Float? = null
    ) : ModelDownloadState

    /** The download stopped with [message]; what it fetched is kept, so Retry resumes. */
    data class Failed(val bundleIds: List<String>, val message: String) : ModelDownloadState

    data class Completed(val bundleIds: List<String>) : ModelDownloadState
}

/**
 * Runs one model download at a time in [scope], outside any screen, so
 * leaving the setup screen does not stop it (issue #10). Started by
 * [com.convoscribe.service.ModelDownloadService], which keeps the
 * process in the foreground while [state] is [ModelDownloadState.Running].
 *
 * Calls come from the main thread; the state writes of a stopped
 * download are dropped, so [cancel] and [interrupt] win over a last
 * progress tick.
 */
class ModelDownloadManager(
    private val downloads: ModelDownloads,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var bundles: List<ModelBundle> = emptyList()

    /** Bumped by every start and stop; a download writes state only while it is current. */
    private var generation = 0

    val isRunning: Boolean get() = _state.value is ModelDownloadState.Running

    /**
     * Download [bundles] unless a download is already running, which
     * the caller then attaches to. Returns `false` in that case.
     */
    fun start(bundles: List<ModelBundle>): Boolean {
        val ids = bundles.map { it.id }
        val myGeneration = synchronized(lock) {
            if (_state.value is ModelDownloadState.Running) return false
            this.bundles = bundles
            _state.value = ModelDownloadState.Running(ids, 0L, bundles.sumOf { it.downloadBytes })
            ++generation
        }

        fun publish(value: ModelDownloadState) = synchronized(lock) {
            if (generation == myGeneration) _state.value = value
        }
        job = scope.launch {
            try {
                downloads.download(bundles).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> publish(
                            ModelDownloadState.Running(
                                bundleIds = ids,
                                bytesDone = event.bytesDone,
                                bytesTotal = event.bytesTotal,
                                currentFile = event.currentFile,
                                waiting = event.waiting,
                                unpacking = event.unpacking
                            )
                        )
                        DownloadEvent.Completed -> publish(ModelDownloadState.Completed(ids))
                    }
                }
            } catch (e: ModelDownloadException) {
                publish(ModelDownloadState.Failed(ids, e.message ?: e.javaClass.simpleName))
            }
        }
        return true
    }

    /**
     * Cancel: stop the running download and delete what it fetched;
     * back to [ModelDownloadState.Idle].
     */
    fun cancel() {
        val (stopped, stoppedBundles) = stop(ModelDownloadState.Idle) ?: return
        scope.launch {
            stopped?.cancelAndJoin()
            withContext(ioDispatcher) { downloads.discardPartial(stoppedBundles) }
        }
    }

    /**
     * Stop the running download but keep what it fetched, so a retry
     * resumes; shown as [ModelDownloadState.Failed] with [message]
     * (the system's time limit for the service ran out).
     */
    fun interrupt(message: String) {
        val ids = (_state.value as? ModelDownloadState.Running)?.bundleIds ?: return
        stop(ModelDownloadState.Failed(ids, message))?.first?.cancel()
    }

    /** Set [next] and detach the running job; `null` when nothing runs. */
    private fun stop(next: ModelDownloadState): Pair<Job?, List<ModelBundle>>? = synchronized(lock) {
        if (_state.value !is ModelDownloadState.Running) return null
        generation++
        _state.value = next
        val stopped = job
        job = null
        stopped?.cancel()
        stopped to bundles
    }
}
