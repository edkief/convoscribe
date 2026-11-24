package com.convoscribe.asr

import java.io.Closeable
import java.io.File

/**
 * Whisper loaded once and kept, decoding one speech segment at a time
 * (issue #160): what live Whisper transcription needs, where loading the
 * model for each segment would cost more than decoding it. Opened for
 * one language by [WhisperRecognizer.openDecoder].
 *
 * Not thread-safe: one [decode] at a time, and [close] only once no
 * [decode] is running.
 */
interface WhisperDecoder : Closeable {

    /** The engine and model decoding, as recorded on the session. */
    val info: EngineInfo

    /**
     * The normalised text of [samples], mono 16 kHz PCM of at most
     * [SpeechChunker.MAX_SAMPLES]; blank when Whisper heard nothing.
     */
    fun decode(samples: FloatArray): String
}

/**
 * An on-device Whisper recognizer (issues #158, #159): transcribes a
 * whole file like any [FileRecognizer], and opens a [WhisperDecoder]
 * that stays loaded for live use.
 */
interface WhisperRecognizer : FileRecognizer {

    /**
     * Load Whisper for [language]. Slow (seconds) and memory-hungry:
     * the caller closes the decoder as soon as it is done with it.
     */
    fun openDecoder(language: String): WhisperDecoder

    /**
     * Transcribe [file] like [transcribe], with [decoder] (from
     * [openDecoder]) already loaded; the caller still closes it. The
     * engine check uses it to time loading apart from decoding (issue
     * #159).
     */
    suspend fun transcribeWith(
        decoder: WhisperDecoder,
        file: File,
        onProgress: (Float) -> Unit = {}
    ): List<TranscriptEvent.Final>
}
