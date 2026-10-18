package com.convoscribe.asr

import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.WavReader
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Transcribe a stored recording (rather than the live microphone) with
 * a [StreamingTranscriber]: the WAV is fed to it chunk by chunk as
 * fast as the engine goes, and the closed utterances come back with
 * times measured from the start of the file.
 *
 * Shared by re-transcription (issue #6) and file import (#3). The
 * caller owns [transcriber] and closes it. Cancellation is checked
 * between chunks, so cancelling the calling coroutine stops the work
 * promptly.
 */
object FileTranscriber {

    /**
     * Feed [file] to [transcriber] and return its non-blank finals in
     * order. [onProgress] gets the fraction of audio processed,
     * `0f..1f`.
     */
    suspend fun transcribe(
        file: File,
        transcriber: StreamingTranscriber,
        chunkSamples: Int = AudioSpec.CHUNK_SAMPLES,
        onProgress: (Float) -> Unit = {}
    ): List<TranscriptEvent.Final> {
        val total = WavReader.samples16k(file).coerceAtLeast(1L)
        val finals = mutableListOf<TranscriptEvent.Final>()
        var done = 0L
        WavReader.readChunks16k(file, chunkSamples) { chunk ->
            currentCoroutineContext().ensureActive()
            transcriber.accept(chunk).collectFinals(finals)
            done += chunk.size
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
        transcriber.finish().collectFinals(finals)
        onProgress(1f)
        return finals
    }

    private fun List<TranscriptEvent>.collectFinals(into: MutableList<TranscriptEvent.Final>) {
        filterIsInstance<TranscriptEvent.Final>().filterTo(into) { it.text.isNotBlank() }
    }
}
