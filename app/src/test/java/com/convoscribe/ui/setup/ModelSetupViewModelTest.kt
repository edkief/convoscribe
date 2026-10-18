package com.convoscribe.ui.setup

import app.cash.turbine.test
import com.convoscribe.models.DownloadEvent
import com.convoscribe.models.ModelBundle
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelDownloadException
import com.convoscribe.models.ModelDownloadManager
import com.convoscribe.models.ModelDownloads
import com.convoscribe.models.ModelStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [ModelSetupViewModel]. The downloader is faked
 * through [com.convoscribe.models.ModelDownloads] behind a real
 * [ModelDownloadManager], and the service start is the manager's own
 * `start`, so we never touch OkHttp, the service or the filesystem
 * (other than [ModelStore.missing] reading the temp folder).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: ModelStore
    private lateinit var modelsRoot: File
    private lateinit var bundle: ModelBundle
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        modelsRoot = tmp.newFolder("models")
        store = ModelStore(modelsRoot)
        // One tiny bundle so `store.missing(...)` is non-empty and
        // `totalBytes` is whatever we declare here.
        bundle = ModelBundle(
            id = "b",
            displayName = "b",
            kind = com.convoscribe.models.ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                com.convoscribe.models.ModelFile(
                    fileName = "b.bin",
                    url = "https://example.invalid/b.bin",
                    sizeBytes = 100L
                )
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A view model over a fresh manager, as the setup screen builds it. */
    private fun viewModel(
        downloads: ModelDownloads,
        bundles: List<ModelBundle> = listOf(bundle),
        manager: ModelDownloadManager = ModelDownloadManager(downloads, CoroutineScope(dispatcher), dispatcher),
        onStart: (List<ModelBundle>) -> Unit = {}
    ): ModelSetupViewModel = ModelSetupViewModel(manager, store, bundles) { toFetch ->
        onStart(toFetch)
        manager.start(toFetch)
    }

    /**
     * Records every call to [download] and lets the test push events
     * through a [MutableSharedFlow]. The first collector wins for a
     * given [MutableSharedFlow] replay buffer; using
     * `replay = Int.MAX_VALUE` lets later assertions still see what
     * was emitted before the subscription.
     */
    private class RecordingDownloads(
        private val events: MutableSharedFlow<DownloadEvent>
    ) : ModelDownloads {
        var callCount: Int = 0
        override fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> {
            callCount += 1
            return events.asSharedFlow()
        }

        override fun discardPartial(bundles: List<ModelBundle>) = Unit
    }

    @Test
    fun start_emitsProgress_thenFinished_andFlagsRunningFalse() = runTest(dispatcher) {
        val events = MutableSharedFlow<DownloadEvent>(replay = Int.MAX_VALUE)
        val downloads = RecordingDownloads(events)
        val vm = viewModel(downloads)

        // Initial state: totalBytes set, not running, not finished.
        assertEquals(100L, vm.state.value.totalBytes)
        assertFalse(vm.state.value.running)
        assertFalse(vm.state.value.finished)

        vm.state.test {
            // First emission is the initial state from the flow's perspective.
            val initial = awaitItem()
            assertEquals(100L, initial.totalBytes)
            assertFalse(initial.running)

            vm.start()
            // After start(): running=true, doneBytes reset.
            val running = awaitItem()
            assertEquals(true, running.running)
            assertEquals(0L, running.doneBytes)
            assertNull(running.error)

            // Drive Progress(50, 100).
            events.tryEmit(DownloadEvent.Progress(bytesDone = 50L, bytesTotal = 100L, currentFile = "b.bin"))
            val mid = awaitItem()
            assertEquals(50L, mid.doneBytes)
            assertEquals(true, mid.running)

            // Complete the flow.
            events.tryEmit(DownloadEvent.Completed)
            val final = awaitItem()
            assertEquals(true, final.finished)
            assertEquals(false, final.running)
            assertEquals(100L, final.doneBytes)
            assertNull(final.error)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, downloads.callCount)
    }

    @Test
    fun start_exception_setsErrorAndStopsRunning_andSecondStartClearsIt() = runTest(dispatcher) {
        val downloads = object : ModelDownloads {
            override fun discardPartial(bundles: List<ModelBundle>) = Unit

            var callCount: Int = 0
            override fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> {
                callCount += 1
                return if (callCount == 1) {
                    // Throw on first call to mimic a download failure.
                    flow { throw ModelDownloadException("HTTP 500 for x") }
                } else {
                    flowOf(DownloadEvent.Completed)
                }
            }
        }
        val vm = viewModel(downloads)

        vm.state.test {
            awaitItem() // initial

            // The Running state in between may be conflated: the
            // failing download can end before the screen collects it.
            vm.start()
            var withError = awaitItem()
            while (withError.error == null) withError = awaitItem()
            assertEquals(false, withError.running)
            assertEquals("HTTP 500 for x", withError.error)
            assertEquals(false, withError.finished)

            // Second start: error is cleared, then completed.
            vm.start()
            var finished = awaitItem()
            while (!finished.finished) {
                assertNull(finished.error)
                finished = awaitItem()
            }
            assertNull(finished.error)
            assertEquals(false, finished.running)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, downloads.callCount)
    }

    @Test
    fun start_calledTwiceWhileRunning_doesNotInvokeDownloaderTwice() = runTest(dispatcher) {
        val events = MutableSharedFlow<DownloadEvent>(replay = Int.MAX_VALUE)
        val downloads = RecordingDownloads(events)
        val vm = viewModel(downloads)

        vm.state.test {
            awaitItem() // initial
            vm.start()
            // First call: running=true.
            awaitItem()
            // Second call while running: no state change because start() is
            // a no-op while running, and we still haven't emitted any
            // events to the download flow.
            vm.start()
            // Advance the dispatcher; the only way callCount goes past 1
            // would be the second start() actually invoking the
            // downloader, which it must not.
            advanceUntilIdle()
            assertEquals(1, downloads.callCount)
            // Drive the flow to completion so the coroutine is observed
            // exiting cleanly.
            events.tryEmit(DownloadEvent.Completed)
            val final = awaitItem()
            assertEquals(true, final.finished)
            assertEquals(false, final.running)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, downloads.callCount)
    }

    /**
     * TASK-28 acceptance criterion: with the English bundles
     * already installed but the French language selected, the
     * setup screen must only fetch the missing French bundle and
     * the [SetupUiState.totalBytes] must equal the bundle's own
     * `totalBytes` (71_052_319). The other required bundles
     * (segmentation + embedding) are language-agnostic and remain
     * installed between sessions, so they must not appear in
     * [ModelStore.missing].
     */
    @Test
    fun start_mixedLanguageAndEngineOnlyDownloadsMissingFrenchBundle() = runTest(dispatcher) {
        // Pre-create the segmentation and embedding bundles on disk
        // so `store.missing(...)` finds them installed and skips
        // them. Only ASR_FR remains to download.
        for (installed in listOf(ModelCatalog.SEGMENTATION, ModelCatalog.EMBEDDING)) {
            for (file in installed.files) {
                val onDisk = File(File(modelsRoot, installed.id), file.fileName)
                onDisk.parentFile?.mkdirs()
                onDisk.writeBytes(ByteArray(file.sizeBytes.toInt()) { 0 })
            }
        }
        // English ASR: not installed; the test passes engine "npu"
        // but `effectiveEngine` maps fr+npu back to cpu so what is
        // actually wanted is `ModelCatalog.ASR_FR`.
        val bundles = ModelCatalog.requiredFor("fr", "npu")
        assertEquals(3, bundles.size)
        assertEquals("asr-fr-kroko-2025-08-06", bundles[0].id)
        // Confirm the store agrees only the French bundle is missing.
        val missing = store.missing(bundles)
        assertEquals(listOf("asr-fr-kroko-2025-08-06"), missing.map { it.id })

        val events = MutableSharedFlow<DownloadEvent>(replay = Int.MAX_VALUE)
        val downloads = RecordingDownloads(events)
        val vm = viewModel(downloads, bundles)

        // Acceptance criterion #1: the UI's advertised total
        // matches the bundle's file-sum, not the (zero for an
        // uncompressed bundle) downloadBytes or any other figure.
        assertEquals(71_052_319L, vm.state.value.totalBytes)

        vm.state.test {
            awaitItem() // initial
            vm.start()
            // running=true, doneBytes reset.
            val running = awaitItem()
            assertEquals(true, running.running)
            assertEquals(0L, running.doneBytes)
            // Complete the download: finished=true, doneBytes==total.
            events.tryEmit(DownloadEvent.Completed)
            val final = awaitItem()
            assertEquals(true, final.finished)
            assertEquals(71_052_319L, final.doneBytes)
            cancelAndIgnoreRemainingEvents()
        }

        // The downloader must have been called exactly once, with
        // only the French bundle (the two English-agnostic
        // models are skipped because they are already installed).
        assertEquals(1, downloads.callCount)
    }

    /**
     * Issue #10: the download outlives the screen. A view model built
     * while a download runs shows its live progress at once, and its
     * Download does not start a second one.
     */
    @Test
    fun newViewModel_attachesToTheRunningDownload() = runTest(dispatcher) {
        val events = MutableSharedFlow<DownloadEvent>(replay = Int.MAX_VALUE)
        val downloads = RecordingDownloads(events)
        val manager = ModelDownloadManager(downloads, CoroutineScope(dispatcher), dispatcher)
        manager.start(listOf(bundle))
        events.tryEmit(
            DownloadEvent.Progress(bytesDone = 30L, bytesTotal = 100L, currentFile = "b.bin", waiting = true)
        )
        advanceUntilIdle()

        var serviceStarts = 0
        val vm = viewModel(downloads, manager = manager, onStart = { serviceStarts++ })
        advanceUntilIdle()

        assertTrue(vm.state.value.running)
        assertEquals(30L, vm.state.value.doneBytes)
        assertTrue(vm.state.value.waitingForNetwork)
        vm.start()
        advanceUntilIdle()
        assertEquals(0, serviceStarts)
        assertEquals(1, downloads.callCount)
    }

    /**
     * The bar follows the bytes on the wire: for an archive bundle
     * those are the compressed body, smaller than [SetupUiState.totalBytes].
     */
    @Test
    fun progress_usesDownloadBytes_andUnpackingWhileExtracting() {
        val downloading = SetupUiState(totalBytes = 600L, doneBytes = 200L, running = true, downloadTotalBytes = 400L)
        assertEquals(0.5f, downloading.progress)
        assertEquals(0.25f, downloading.copy(unpacking = 0.25f).progress)
        assertEquals(0.5f, SetupUiState(totalBytes = 100L, doneBytes = 50L).progress)
    }
}
