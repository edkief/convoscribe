package com.convoscribe.asr

import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.WavReader
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A voice activity detector seen through the one thing
 * [SpeechChunker] needs from it: feed audio, then collect the speech
 * segments it has closed. Backed by sherpa-onnx's Silero VAD on the
 * device ([com.convoscribe.engine.sherpa.SherpaVoiceDetector]) and by
 * a plain fake in tests.
 */
interface VoiceDetector : Closeable {

    /** Push mono 16 kHz float PCM; any length. */
    fun accept(samples: FloatArray)

    /** No more audio: close the segment in progress, if any. */
    fun flush()

    /**
     * The speech segments closed since the last call, oldest first,
     * removed from the detector. [SpeechSpan.startSample] counts from
     * the first sample passed to [accept].
     */
    fun drain(): List<SpeechSpan>
}

/** One stretch of speech: where it starts in the recording and its samples. */
class SpeechSpan(val startSample: Long, val samples: FloatArray) {
    val startMs: Long get() = startSample * 1000 / AudioSpec.SAMPLE_RATE
    val endMs: Long get() = (startSample + samples.size) * 1000 / AudioSpec.SAMPLE_RATE
}

/**
 * Cuts a stored recording into speech segments short enough for an
 * offline recognizer with a fixed input window (Whisper reads at most
 * 30 s, issue #158). The [VoiceDetector] finds the speech; a segment
 * longer than `maxSamples` is cut into equal pieces of at most that
 * length, so a long monologue still fits.
 *
 * Cancellation is checked between chunks, like [FileTranscriber].
 */
object SpeechChunker {

    /** 28 s: inside Whisper's 30 s window with room for its tail padding. */
    const val MAX_SAMPLES: Int = 28 * AudioSpec.SAMPLE_RATE

    /**
     * Feed [file] to [detector] and call [onSpan] with each speech
     * segment, in order, as soon as it is closed. [onProgress] gets
     * the fraction of the file read, `0f..1f`.
     */
    suspend fun chunk(
        file: File,
        detector: VoiceDetector,
        chunkSamples: Int = AudioSpec.CHUNK_SAMPLES,
        maxSamples: Int = MAX_SAMPLES,
        onProgress: (Float) -> Unit = {},
        onSpan: suspend (SpeechSpan) -> Unit
    ) {
        require(maxSamples > 0) { "maxSamples must be positive" }
        val total = WavReader.samples16k(file).coerceAtLeast(1L)
        var done = 0L
        WavReader.readChunks16k(file, chunkSamples) { chunk ->
            currentCoroutineContext().ensureActive()
            detector.accept(chunk)
            for (span in detector.drain()) split(span, maxSamples).forEach { onSpan(it) }
            done += chunk.size
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
        detector.flush()
        for (span in detector.drain()) split(span, maxSamples).forEach { onSpan(it) }
        onProgress(1f)
    }

    /** [span] cut into the fewest equal pieces of at most [maxSamples]. */
    internal fun split(span: SpeechSpan, maxSamples: Int): List<SpeechSpan> {
        val size = span.samples.size
        if (size <= maxSamples) return listOf(span)
        val pieces = (size + maxSamples - 1) / maxSamples
        val step = (size + pieces - 1) / pieces
        return (0 until pieces).map { i ->
            val from = i * step
            val to = minOf(size, from + step)
            SpeechSpan(span.startSample + from, span.samples.copyOfRange(from, to))
        }
    }
}
