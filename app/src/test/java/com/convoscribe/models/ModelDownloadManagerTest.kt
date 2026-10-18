package com.convoscribe.models

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ModelDownloadManager]: one download at a time, state transitions, cancel and interrupt. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadManagerTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private val bundle = ModelBundle(
        id = "seg",
        displayName = "seg",
        kind = ModelKind.SEGMENTATION,
        language = null,
        files = listOf(ModelFile("a.bin", "http://x/a.bin", 1_000))
    )

    /** Emits whatever the test sends into [events]; records every call. */
    private class FakeDownloads : ModelDownloads {
        val events = Channel<DownloadEvent>(Channel.UNLIMITED)
        var downloadCalls = 0
        val discarded = mutableListOf<List<String>>()
        var failWith: ModelDownloadException? = null

        override fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> {
            downloadCalls++
            val failure = failWith
            return if (failure != null) flow { throw failure } else events.receiveAsFlow()
        }

        override fun discardPartial(bundles: List<ModelBundle>) {
            discarded += bundles.map { it.id }
        }
    }

    private val downloads = FakeDownloads()
    private val manager = ModelDownloadManager(downloads, scope, ioDispatcher = dispatcher)

    @Test
    fun start_runsAndReportsProgress_thenCompleted() {
        assertTrue(manager.start(listOf(bundle)))
        assertEquals(ModelDownloadState.Running(listOf("seg"), 0L, 1_000L), manager.state.value)

        downloads.events.trySend(DownloadEvent.Progress(400L, 1_000L, "a.bin", waiting = true))
        scope.runCurrent()
        assertEquals(
            ModelDownloadState.Running(listOf("seg"), 400L, 1_000L, "a.bin", waiting = true),
            manager.state.value
        )

        downloads.events.trySend(DownloadEvent.Completed)
        downloads.events.close()
        scope.runCurrent()
        assertEquals(ModelDownloadState.Completed(listOf("seg")), manager.state.value)
        assertFalse(manager.isRunning)
    }

    @Test
    fun secondStart_whileRunning_attachesInsteadOfStartingAnother() {
        assertTrue(manager.start(listOf(bundle)))
        scope.runCurrent()

        assertFalse(manager.start(listOf(bundle)))
        scope.runCurrent()

        assertEquals(1, downloads.downloadCalls)
    }

    @Test
    fun failure_isReportedWithItsMessage_andAStartAfterwardsRunsAgain() {
        downloads.failWith = ModelDownloadException("HTTP 404 for a.bin")
        manager.start(listOf(bundle))
        scope.runCurrent()

        assertEquals(ModelDownloadState.Failed(listOf("seg"), "HTTP 404 for a.bin"), manager.state.value)
        assertTrue(downloads.discarded.isEmpty())

        downloads.failWith = null
        assertTrue(manager.start(listOf(bundle)))
        scope.runCurrent()
        assertEquals(2, downloads.downloadCalls)
    }

    @Test
    fun cancel_stopsTheDownload_deletesItsParts_andGoesIdle() {
        manager.start(listOf(bundle))
        scope.runCurrent()

        manager.cancel()
        // A tick racing the cancel must not bring the state back.
        downloads.events.trySend(DownloadEvent.Progress(500L, 1_000L, "a.bin"))
        scope.runCurrent()

        assertEquals(ModelDownloadState.Idle, manager.state.value)
        assertEquals(listOf(listOf("seg")), downloads.discarded)
    }

    @Test
    fun interrupt_stopsTheDownload_keepsItsParts_andFailsWithTheMessage() {
        manager.start(listOf(bundle))
        scope.runCurrent()

        manager.interrupt("Paused by the system")
        scope.runCurrent()

        assertEquals(ModelDownloadState.Failed(listOf("seg"), "Paused by the system"), manager.state.value)
        assertTrue(downloads.discarded.isEmpty())
    }

    @Test
    fun cancel_whenIdle_doesNothing() {
        manager.cancel()
        scope.runCurrent()

        assertEquals(ModelDownloadState.Idle, manager.state.value)
        assertTrue(downloads.discarded.isEmpty())
    }
}
