package com.convoscribe.enginecheck

import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelDownloadState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperNpuAutoCheckTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val downloads = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    private val sample = File("sample.wav")
    private val runs = mutableListOf<File>()
    private var gate: CompletableDeferred<Unit>? = null

    private val probe = WhisperNpuProbe { file ->
        runs += file
        gate?.await()
        EngineCheckResult(WHISPER_NPU_ENGINE, 0, false, 1_000, 100, "hello")
    }

    private val check = WhisperNpuAutoCheck(downloads, probe, { sample }, scope).also { it.start() }

    private val npuBundle = ModelCatalog.whisperNpuAssets.first().bundle.id

    @Test
    fun completedNpuDownload_runsTheProbeOnce() {
        downloads.value = ModelDownloadState.Running(listOf(npuBundle), 0, 1)
        downloads.value = ModelDownloadState.Completed(listOf(npuBundle, ModelCatalog.VAD_SILERO.id))
        scope.runCurrent()

        assertEquals(listOf(sample), runs)
        assertFalse(check.checking.value)
    }

    @Test
    fun otherDownloads_doNotRunIt() {
        downloads.value = ModelDownloadState.Completed(ModelCatalog.whisperBundles.map { it.id })
        downloads.value = ModelDownloadState.Failed(listOf(npuBundle), "network")
        scope.runCurrent()

        assertTrue(runs.isEmpty())
    }

    @Test
    fun checking_isTrueWhileTheProbeRuns_andASecondCheckIsSkipped() {
        gate = CompletableDeferred()
        downloads.value = ModelDownloadState.Completed(listOf(npuBundle))
        scope.runCurrent()
        assertTrue(check.checking.value)

        scope.backgroundScope.launch { check.check() }
        scope.runCurrent()
        assertEquals(1, runs.size)

        gate!!.complete(Unit)
        scope.runCurrent()
        assertFalse(check.checking.value)
    }

    @Test
    fun aProbeThatCannotStart_isSwallowed() {
        val failing = WhisperNpuAutoCheck(downloads, { error("cannot bind") }, { sample }, scope).also { it.start() }
        downloads.value = ModelDownloadState.Completed(listOf(npuBundle))
        scope.runCurrent()

        assertFalse(failing.checking.value)
    }
}
