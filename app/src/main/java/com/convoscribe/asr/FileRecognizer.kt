package com.convoscribe.asr

import java.io.File

/**
 * Recognises a whole stored recording in one go, as opposed to
 * [FileTranscriber], which feeds it to a [StreamingTranscriber] chunk
 * by chunk. Implemented by the transcription server client (issue
 * #58, [com.convoscribe.asr.server.ServerTranscriber]); the
 * re-transcribe and import use cases pick one or the other.
 */
interface FileRecognizer {

    /**
     * Transcribe the 16 kHz mono WAV [file] in [language] and return
     * its non-blank closed utterances in order, with times (and token
     * times) in milliseconds from the start of the file. [onProgress]
     * gets the fraction done, `0f..1f`. Cancelling the calling
     * coroutine aborts the work.
     */
    suspend fun transcribe(file: File, language: String, onProgress: (Float) -> Unit = {}): List<TranscriptEvent.Final>

    /**
     * The engine and model that produced the last [transcribe] result,
     * stored on the session like [TranscriberFactory.describe].
     */
    fun describe(): EngineInfo
}
