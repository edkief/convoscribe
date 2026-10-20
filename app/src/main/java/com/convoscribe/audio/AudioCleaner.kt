package com.convoscribe.audio

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A streaming speech cleaner (issue #37): noisy 16 kHz mono float
 * audio in, voice-focused audio out. Used live between the
 * microphone and the recognizer, and over a stored recording by
 * [AudioCleaning.cleanFile].
 *
 * The engine works in frames, so [process] may return fewer samples
 * than it was given (the rest is held back until the next call), and
 * [flush] returns what is left. Over a whole run the output is
 * sample-aligned with the input: output sample `i` is the cleaned
 * input sample `i`, and the total out equals the total in. That keeps
 * the cleaned recording in step with the original, so transcript
 * times, marks and seeking match both files. [aligned] enforces it
 * for any engine.
 */
interface StreamingCleaner : Closeable {

    /** Clean [samples] and return the cleaned audio available so far. */
    fun process(samples: FloatArray): FloatArray

    /**
     * No more input for now: return the rest of the cleaned audio.
     * The cleaner is ready for a fresh run afterwards (e.g. after a
     * pause), with its count starting again from zero.
     */
    fun flush(): FloatArray
}

/**
 * Builds a [StreamingCleaner] without the caller depending on the
 * engine. One cleaner per run, never shared across threads.
 */
interface AudioCleanerFactory {

    /** Whether the cleanup model is installed, so [create] can succeed. */
    fun isAvailable(): Boolean

    /**
     * A ready cleaner. Throws [IllegalStateException] when the model
     * is not installed.
     */
    fun create(): StreamingCleaner

    /** Id of the model [create] uses, stored on a cleaned session. */
    fun modelId(): String
}

/**
 * Wrap [this] so its output is exactly as long as its input over each
 * run: whatever the engine still owes at [StreamingCleaner.flush] is
 * padded with silence, and anything beyond the input is dropped.
 */
fun StreamingCleaner.aligned(): StreamingCleaner = this as? AlignedCleaner ?: AlignedCleaner(this)

private class AlignedCleaner(private val inner: StreamingCleaner) : StreamingCleaner {
    private var fedIn = 0L
    private var givenOut = 0L

    override fun process(samples: FloatArray): FloatArray {
        fedIn += samples.size
        return take(inner.process(samples))
    }

    override fun flush(): FloatArray {
        val tail = take(inner.flush())
        val missing = (fedIn - givenOut).toInt()
        fedIn = 0L
        givenOut = 0L
        return if (missing > 0) tail + FloatArray(missing) else tail
    }

    /** [out], cut so the total given never runs ahead of the input. */
    private fun take(out: FloatArray): FloatArray {
        val room = (fedIn - givenOut).coerceAtLeast(0L)
        val kept = if (out.size > room) out.copyOf(room.toInt()) else out
        givenOut += kept.size
        return kept
    }

    override fun close() = inner.close()
}

/** Cleaning a stored recording, for imports and finished sessions. */
object AudioCleaning {

    /**
     * Clean [source] into [target], a 16 kHz mono PCM16 WAV with the
     * same number of samples. Streams the file, so memory use does
     * not grow with its length. [onProgress] gets the fraction of
     * audio done, `0f..1f`. Cancellation is checked between chunks;
     * the caller owns [cleaner] and deletes [target] on failure.
     */
    suspend fun cleanFile(
        source: File,
        target: File,
        cleaner: StreamingCleaner,
        chunkSamples: Int = AudioSpec.CHUNK_SAMPLES,
        onProgress: (Float) -> Unit = {}
    ) {
        val aligned = cleaner.aligned()
        val total = WavReader.samples16k(source).coerceAtLeast(1L)
        var done = 0L
        WavWriter(target).use { out ->
            WavReader.readChunks16k(source, chunkSamples) { chunk ->
                currentCoroutineContext().ensureActive()
                out.write(aligned.process(chunk).toPcm16Samples())
                done += chunk.size
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
            out.write(aligned.flush().toPcm16Samples())
        }
        onProgress(1f)
    }
}
