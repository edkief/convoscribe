package com.convoscribe.recording

import com.convoscribe.audio.AudioCleaning
import com.convoscribe.audio.StreamingCleaner
import com.convoscribe.audio.WavWriter
import com.convoscribe.audio.aligned
import com.convoscribe.audio.toPcm16Samples
import java.io.Closeable
import java.io.File

/**
 * The cleaned half of a live recording (issue #37): runs each chunk
 * through [cleaner] and writes the result to the session's cleaned
 * WAV, which stays sample-aligned with the original. The recognizer
 * gets [AudioCleaning.forRecognizer] of the cleaned audio and the
 * original it came from, which the cleaner returns a little later
 * than it was given; [held] keeps that original until then.
 *
 * With no [cleaner] (a cleaned session continued after its model was
 * removed) the audio is written as it is, so the two files still line
 * up.
 */
internal class CleanTrack private constructor(
    cleaner: StreamingCleaner?,
    private val wav: WavWriter
) : Closeable {

    private val cleaner: StreamingCleaner? = cleaner?.aligned()

    /** Original audio given to [cleaner] whose cleaned version has not come back yet: [heldCount] samples. */
    private var held = FloatArray(0)
    private var heldCount = 0

    /**
     * Clean [samples], append the cleaned audio to the cleaned WAV and
     * return what the recognizer should get for it.
     */
    fun process(samples: FloatArray): FloatArray {
        val cleaner = cleaner ?: return samples.also { wav.write(it.toPcm16Samples()) }
        if (heldCount + samples.size > held.size) held = held.copyOf(heldCount + samples.size)
        samples.copyInto(held, heldCount)
        heldCount += samples.size
        return forRecognizer(cleaner.process(samples))
    }

    /**
     * Write out what the cleaner still holds, at a pause or a stop, so
     * the cleaned WAV catches up with the original, and return it for
     * the recognizer.
     */
    fun flush(): FloatArray {
        val cleaner = cleaner ?: return FloatArray(0)
        return forRecognizer(cleaner.flush()).also { heldCount = 0 }
    }

    /** Write [cleaned] to the cleaned WAV and blend it with the original it came from. */
    private fun forRecognizer(cleaned: FloatArray): FloatArray {
        wav.write(cleaned.toPcm16Samples())
        val original = held.copyOf(cleaned.size)
        held.copyInto(held, 0, cleaned.size, heldCount)
        heldCount -= cleaned.size
        return AudioCleaning.forRecognizer(cleaned, original)
    }

    override fun close() {
        try {
            cleaner?.close()
        } finally {
            wav.close()
        }
    }

    companion object {
        /**
         * Open [file] for a run whose original WAV already holds
         * [originalSamples] (0 for a new session). A continued
         * session's cleaned file that fell behind (a crash before the
         * last flush) is padded with silence first, so cleaned sample
         * `i` keeps matching original sample `i`.
         */
        fun open(file: File, cleaner: StreamingCleaner?, originalSamples: Long): CleanTrack {
            val wav = WavWriter(file, append = originalSamples > 0)
            try {
                var behind = originalSamples - wav.samplesWritten
                while (behind > 0) {
                    val step = minOf(behind, PAD_CHUNK.toLong()).toInt()
                    wav.write(ShortArray(step))
                    behind -= step
                }
            } catch (t: Throwable) {
                runCatching { wav.close() }
                runCatching { cleaner?.close() }
                throw t
            }
            return CleanTrack(cleaner, wav)
        }

        private const val PAD_CHUNK = 16_000
    }
}
