package com.convoscribe.enginecheck

import com.convoscribe.asr.WhisperRecognizer
import com.convoscribe.audio.WavReader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Time on-device Whisper (issue #158) over the sample clip [sampleFile]
 * in English, reported as [engine] ([WHISPER_ENGINE] or
 * [WHISPER_NPU_ENGINE], issue #159). Loading the model
 * ([WhisperRecognizer.openDecoder], seconds for the 2 GB NPU build) is
 * [EngineCheckResult.initMs] and transcribing the clip is
 * [EngineCheckResult.processMs], so the real-time factor is the
 * decoding speed alone, as in a live recording, which keeps the model
 * loaded (issue #160). A failure becomes [EngineCheckResult.error],
 * never an exception.
 */
suspend fun measureWhisper(
    recognizer: WhisperRecognizer,
    sampleFile: File,
    engine: String = WHISPER_ENGINE,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    clock: () -> Long
): EngineCheckResult {
    var audioMs = 0L
    return try {
        audioMs = WavReader.samples16k(sampleFile) / 16L
        val loadStart = clock()
        withContext(dispatcher) { recognizer.openDecoder("en") }.use { decoder ->
            val initMs = clock() - loadStart
            val start = clock()
            val finals = recognizer.transcribeWith(decoder, sampleFile)
            EngineCheckResult(
                engine = engine,
                initMs = initMs,
                firstRun = false,
                audioMs = audioMs,
                processMs = clock() - start,
                text = finals.joinToString(" ") { it.text }
            )
        }
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
