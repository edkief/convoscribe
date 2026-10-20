package com.convoscribe.ui.enginecheck

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.audio.WavReader
import com.convoscribe.enginecheck.CleanupCheckResult
import com.convoscribe.enginecheck.DeviceInfo
import com.convoscribe.enginecheck.EngineCheckResult
import com.convoscribe.enginecheck.EngineCheckRunner
import com.convoscribe.enginecheck.LiveCleanupStore
import com.convoscribe.enginecheck.LiveCleanupVerdict
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
 * @property cleanupAvailable the noise cleanup model is installed, so
 *   live cleaning can be checked (issue #37).
 * @property cleanupRunning the live-cleaning check is in flight.
 * @property cleanup the cleaner's own timing, from the last check.
 * @property cleanupEngine the ASR engine the last live-cleaning check
 *   was combined with (the one Settings select).
 * @property liveVerdict whether live cleaning is recommended with
 *   [cleanupEngine], from the combined real-time factor.
 */
data class EngineCheckUiState(
    val deviceInfo: DeviceInfo? = null,
    val results: Map<String, EngineCheckResult> = emptyMap(),
    val runningEngine: String? = null,
    val recordingBlocked: Boolean = false,
    val modelMissing: Boolean = false,
    val missingEngine: String? = null,
    val cleanupAvailable: Boolean = false,
    val cleanupRunning: Boolean = false,
    val cleanup: CleanupCheckResult? = null,
    val cleanupEngine: String? = null,
    val liveVerdict: LiveCleanupVerdict? = null
) {
    /** Recognizer plus cleaner, for [cleanupEngine]; `null` until both ran cleanly. */
    val combinedRtf: Double?
        get() {
            val asr = results[cleanupEngine]?.takeIf { it.error == null } ?: return null
            val clean = cleanup?.takeIf { it.error == null } ?: return null
            return asr.realTimeFactor + clean.realTimeFactor
        }
}

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
 * Times the noise cleaner over the sample clip (issue #37), or says
 * it is unavailable. Production wraps [measureCleanup] around the
 * container's cleaner factory.
 */
interface CleanupProbe {
    fun isAvailable(): Boolean
    fun run(sampleFile: File): CleanupCheckResult
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
    private val npuProbe: NpuProbe,
    private val cleanupProbe: CleanupProbe? = null,
    private val liveCleanup: LiveCleanupStore? = null,
    private val currentEngine: () -> String = { "cpu" }
) : ViewModel() {

    private val _state = MutableStateFlow(EngineCheckUiState(cleanupAvailable = cleanupProbe?.isAvailable() == true))
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
        viewModelScope.launch { checkEngine(engine) }
    }

    /**
     * Check live noise cleanup (issue #37) with the engine Settings
     * select: run that engine's check first if it has not run, time
     * the cleaner over the same clip, and record whether the two
     * together keep up with live audio. The Record screen and
     * Settings read the verdict from [LiveCleanupStore].
     */
    fun runCleanupCheck() {
        val probe = cleanupProbe ?: return
        ensureDeviceInfo()
        if (recordingController.state.value.phase != RecordingPhase.IDLE) {
            _state.value = _state.value.copy(recordingBlocked = true)
            return
        }
        val engine = currentEngine()
        val bundle = ModelCatalog.asrFor("en", engine)
        if (!isInstalled(bundle)) {
            _state.value = _state.value.copy(modelMissing = true, missingEngine = engine)
            return
        }
        _state.value = _state.value.copy(
            cleanupRunning = true,
            recordingBlocked = false,
            modelMissing = false,
            missingEngine = null
        )
        viewModelScope.launch {
            if (_state.value.results[engine] == null) {
                _state.value = _state.value.copy(runningEngine = engine)
                checkEngine(engine)
            }
            val cleanup = withContext(Dispatchers.Default) { probe.run(sampleFile(bundle)) }
            _state.value = _state.value.copy(cleanup = cleanup, cleanupEngine = engine, cleanupRunning = false)
            val combined = _state.value.combinedRtf
            if (combined != null) {
                liveCleanup?.record(engine, combined)
                _state.value = _state.value.copy(liveVerdict = LiveCleanupVerdict.of(combined))
            } else {
                _state.value = _state.value.copy(liveVerdict = null)
            }
        }
    }

    private fun sampleFile(bundle: ModelBundle) = File(File(context.filesDir, "models/${bundle.id}"), "test_wavs/0.wav")

    /** Run [engine]'s check and store its result; [EngineCheckUiState.runningEngine] must already be set. */
    private suspend fun checkEngine(engine: String) {
        val sampleFile = sampleFile(ModelCatalog.asrFor("en", engine))
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

    /**
     * Build the plain-text report the user copies / shares.
     * Returns an empty string when no check has run yet so the
     * screen can disable the buttons.
     */
    fun buildReport(): String {
        val info = _state.value.deviceInfo ?: return ""
        val list = listOf("npu", "cpu")
            .mapNotNull { _state.value.results[it] }
        val report = formatReport(info, list)
        val cleanup = _state.value.cleanup ?: return report
        return buildString {
            append(report)
            if (!report.endsWith("\n")) append('\n')
            append("\n--- Noise cleanup ---\n")
            if (cleanup.error != null) {
                append("Error: ").append(cleanup.error).append('\n')
            } else {
                append("RTF: ").append("%.2f".format(cleanup.realTimeFactor)).append('\n')
            }
            _state.value.combinedRtf?.let { combined ->
                append("With ").append(_state.value.cleanupEngine.orEmpty().uppercase()).append(": ")
                append("%.2f".format(combined)).append(" (").append(LiveCleanupVerdict.of(combined).name).append(")\n")
            }
        }
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
