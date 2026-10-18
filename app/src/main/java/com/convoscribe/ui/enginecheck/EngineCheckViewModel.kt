package com.convoscribe.ui.enginecheck

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.audio.WavReader
import com.convoscribe.enginecheck.DeviceInfo
import com.convoscribe.enginecheck.EngineCheckResult
import com.convoscribe.enginecheck.EngineCheckRunner
import com.convoscribe.enginecheck.formatReport
import com.convoscribe.enginecheck.storageWarning
import com.convoscribe.models.ModelBundle
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingPhase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State shown by the engine-check screen.
 *
 * @property deviceInfo the snapshot used to render the device
 *   card at the top; `null` until the first composition hands the
 *   `Context` to [EngineCheckViewModel.ensureDeviceInfo].
 * @property results per-engine check results, keyed by engine
 *   name (`"npu"`, `"cpu"`). The UI shows a card for each
 *   successful entry and "not run yet" for the missing ones.
 * @property runningEngine name of the engine that is currently
 *   being checked (`"npu"` or `"cpu"`); `null` when no check is
 *   in flight.
 * @property recordingBlocked `true` when the user tapped a check
 *   button while a recording session is still active. The UI
 *   reads this to swap the button label for the "Stop the
 *   recording first" hint.
 * @property modelMissing `true` when the engine's English bundle
 *   isn't installed yet (so the screen shows "Download the
 *   `<engine>` model first").
 * @property missingEngine engine whose model is missing, or
 *   `null` if every model's installed.
 */
data class EngineCheckUiState(
    val deviceInfo: DeviceInfo? = null,
    val results: Map<String, EngineCheckResult> = emptyMap(),
    val runningEngine: String? = null,
    val recordingBlocked: Boolean = false,
    val modelMissing: Boolean = false,
    val missingEngine: String? = null
)

/**
 * Function the ViewModel calls to build an in-process runner for
 * [engine]. Kept as an interface so the screen tests can swap a
 * synchronous runner without instantiating a real
 * [com.convoscribe.asr.TranscriberFactory] and writing real WAV
 * files. The production implementation pins the container's
 * factory to [engine], so "Check CPU" tests the CPU even when
 * Settings select the NPU.
 */
fun interface EngineCheckRunnerFactory {
    fun create(engine: String, sampleFile: File, clock: () -> Long): EngineCheckRunner
}

/**
 * Runs the NPU check out of process. Production wires
 * [com.convoscribe.enginecheck.NpuProbeClient]: the QNN runtime
 * calls `exit()` on some devices, which must not close the app.
 */
fun interface NpuProbe {
    suspend fun run(sampleFile: File): EngineCheckResult
}

/**
 * ViewModel for the engine-check screen. Owns one
 * [EngineCheckUiState] `StateFlow` that the screen collects.
 *
 * The expensive checks (running the audio through a recognizer)
 * are kicked off in [viewModelScope] on [Dispatchers.Default] so
 * the UI thread stays free; the device-info snapshot is read on
 * first composition and is therefore a plain read, not a
 * coroutine.
 */
class EngineCheckViewModel(
    private val context: Context,
    private val recordingController: RecordingController,
    private val runnerFactory: EngineCheckRunnerFactory,
    private val npuProbe: NpuProbe
) : ViewModel() {

    private val _state = MutableStateFlow(EngineCheckUiState())
    val state: StateFlow<EngineCheckUiState> = _state.asStateFlow()

    /**
     * Latest storage-warning string (`null` when there is nothing
     * to warn about). Stored on the ViewModel because the
     * device-info snapshot is computed once and the warning is
     * then a pure function of two values that never change.
     */
    /** `true` when free storage is too low to prepare the NPU model (see [storageWarning]). */
    var storageLow: Boolean = false
        private set

    /**
     * Read the device snapshot on the calling (main) thread. Safe
     * to call from a composable's first composition — the read
     * only touches the filesystem and `Build`, no recognizer is
     * built.
     */
    fun ensureDeviceInfo() {
        if (_state.value.deviceInfo != null) return
        val info = DeviceInfo.read(context)
        val npuBundle = ModelCatalog.ASR_EN_NPU
        val npuPrepared = isNpuPrepared(npuBundle)
        val warning = storageWarning(info.freeStorageBytes, npuPrepared)
        storageLow = warning != null
        _state.value = _state.value.copy(deviceInfo = info)
    }

    /**
     * Whether every QNN context binary in [bundle] is on disk.
     * The engine check treats the NPU as "prepared" when all
     * three context binaries exist; partial reads are still
     * considered unprepared so the storage warning fires until
     * the first run completes.
     */
    private fun isNpuPrepared(bundle: ModelBundle): Boolean {
        val dir = File(File(context.filesDir, "models"), bundle.id)
        return listOf("encoder.bin", "decoder.bin", "joiner.bin").all {
            File(dir, it).exists()
        }
    }

    /**
     * Kick off the engine check for [engine]. Returns immediately
     * — the screen observes [UIState.runningEngine] for the
     * "Running…" label.
     *
     * If a recording is active we flip
     * [EngineCheckUiState.recordingBlocked] instead and do not
     * start a check; the screen's button then reads as "Stop the
     * recording first".
     */
    fun runCheck(engine: String) {
        ensureDeviceInfo()
        if (recordingController.state.value.phase != RecordingPhase.IDLE) {
            _state.value = _state.value.copy(recordingBlocked = true)
            return
        }
        val bundle = ModelCatalog.asrFor("en", engine)
        if (!isInstalled(bundle)) {
            _state.value = _state.value.copy(
                runningEngine = null,
                modelMissing = true,
                missingEngine = engine
            )
            return
        }
        _state.value = _state.value.copy(
            runningEngine = engine,
            recordingBlocked = false,
            modelMissing = false,
            missingEngine = null
        )
        viewModelScope.launch {
            val sampleFile = File(File(context.filesDir, "models/${bundle.id}"), "test_wavs/0.wav")
            val result = if (engine == "npu") {
                try {
                    npuProbe.run(sampleFile)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    EngineCheckResult(
                        engine = engine,
                        initMs = 0L,
                        firstRun = false,
                        audioMs = 0L,
                        processMs = 0L,
                        text = "",
                        error = e.message ?: e.toString()
                    )
                }
            } else {
                withContext(Dispatchers.Default) {
                    val runner = runnerFactory.create(engine, sampleFile, clock = { System.nanoTime() / 1_000_000L })
                    runner.run(engine)
                }
            }
            val next = _state.value.results.toMutableMap()
            next[engine] = result
            _state.value = _state.value.copy(
                results = next,
                runningEngine = null
            )
        }
    }

    /**
     * Build the plain-text report the user copies / shares.
     * Returns an empty string when no check has run yet so the
     * screen can disable the buttons.
     */
    fun buildReport(): String {
        val info = _state.value.deviceInfo ?: return ""
        val list = listOf("npu", "cpu")
            .mapNotNull { _state.value.results[it] }
        return formatReport(info, list)
    }

    /**
     * True when every file in [bundle] is present on disk. Mirrors
     * `ModelStore.isInstalled` without dragging the store into the
     * ViewModel — the engine check only needs the on/off answer
     * for its own screen.
     */
    private fun isInstalled(bundle: ModelBundle): Boolean = bundle.files.all { file ->
        val onDisk = File(File(File(context.filesDir, "models"), bundle.id), file.fileName)
        onDisk.exists() && onDisk.length() == file.sizeBytes
    }

    /**
     * Clear the recording-blocked latch so the user can retry
     * once the session has finished. The screen calls this when
     * the recording state drops back to `IDLE`.
     */
    fun clearRecordingBlocked() {
        if (_state.value.recordingBlocked) {
            _state.value = _state.value.copy(recordingBlocked = false)
        }
    }
}

/**
 * Read the audio samples the runner will chew on. Public so the
 * production runner factory can call it without an awkward
 * `ViewModel` round-trip; not part of the public API surface —
 * only consumed by `AppNavHost`'s wiring.
 */
fun readEngineCheckSamples(file: File): FloatArray = WavReader.readMono16k(file)
