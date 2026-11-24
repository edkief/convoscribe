package com.convoscribe.enginecheck

import com.convoscribe.asr.FileRecognizer
import com.convoscribe.audio.WavReader
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Time on-device Whisper (issue #158) over the sample clip [sampleFile]
 * in English, reported as [engine] ([WHISPER_ENGINE] or
 * [WHISPER_NPU_ENGINE], issue #159). The recognizer loads its model inside each call, so
 * [EngineCheckResult.processMs] includes loading; [EngineCheckResult.initMs]
 * stays 0. A failure becomes [EngineCheckResult.error], never an exception.
 */
suspend fun measureWhisper(
    recognizer: FileRecognizer,
    sampleFile: File,
    engine: String = WHISPER_ENGINE,
    clock: () -> Long
): EngineCheckResult {
    var audioMs = 0L
    return try {
        audioMs = WavReader.samples16k(sampleFile) / 16L
        val start = clock()
        val finals = recognizer.transcribe(sampleFile, "en")
        EngineCheckResult(
            engine = engine,
            initMs = 0L,
            firstRun = false,
            audioMs = audioMs,
            processMs = clock() - start,
            text = finals.joinToString(" ") { it.text }
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        EngineCheckResult(
            engine = engine,
            initMs = 0L,
            firstRun = false,
            audioMs = audioMs,
            processMs = 0L,
            text = "",
            error = t.message ?: t.toString()
        )
    }
}

/** [EngineCheckResult.engine] of a Whisper check: Whisper on the CPU. */
const val WHISPER_ENGINE: String = "whisper-cpu"

/** [EngineCheckResult.engine] of a Whisper check on the NPU (issue #159). */
const val WHISPER_NPU_ENGINE: String = "whisper-npu"
