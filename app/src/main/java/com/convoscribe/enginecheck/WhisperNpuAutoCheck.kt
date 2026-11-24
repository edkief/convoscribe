package com.convoscribe.enginecheck

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.audio.WavWriter
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelDownloadState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Checks Whisper on the NPU (issue #159) in the probe process as soon as
 * a download that brought its NPU build completes, so the NPU is used
 * without a trip to Engine check (and a build the chip cannot run is
 * recorded before a recording depends on it). Runs in the main process
 * only ([start] is called from [com.convoscribe.ConvoApp]).
 */
class WhisperNpuAutoCheck(
    private val downloads: Flow<ModelDownloadState>,
    private val probe: WhisperNpuProbe,
    private val sample: () -> File,
    private val scope: CoroutineScope
) {
    private val _checking = MutableStateFlow(false)

    /** The probe is running; Settings says "Checking the NPU…". */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun start() {
        scope.launch {
            downloads.collect { state ->
                if (state is ModelDownloadState.Completed && state.bundleIds.any(::isWhisperNpu)) check()
            }
        }
    }

    /** Run the probe now (also after Settings re-downloads or asks again). */
    suspend fun check() {
        if (_checking.value) return
        _checking.value = true
        try {
            val result = probe.runWhisper(sample())
            Log.i(TAG, "Whisper NPU check: error=${result.error}, rtf=${result.realTimeFactor}")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Whisper NPU check could not run", t)
        } finally {
            _checking.value = false
        }
    }

    private fun isWhisperNpu(id: String) = ModelCatalog.whisperNpuAssets.any { it.bundle.id == id }
}

/**
 * The clip a Whisper check runs on: the English model's sample, which
 * comes with the English CPU (or NPU) model, or else a short silent
 * clip written to [cacheDir]. On silence the VAD finds no speech, so
 * that run checks that the NPU loads the model, not decoding.
 */
fun whisperSampleFile(filesDir: File, cacheDir: File): File {
    listOf(ModelCatalog.ASR_EN, ModelCatalog.ASR_EN_NPU)
        .map { File(File(filesDir, "models/${it.id}"), "test_wavs/0.wav") }
        .firstOrNull { it.exists() }
        ?.let { return it }
    val silent = File(cacheDir, "whisper-check-silence.wav")
    if (!silent.exists()) {
        silent.parentFile?.mkdirs()
        WavWriter(silent).use { it.write(ShortArray(2 * 16_000)) }
    }
    return silent
}
