package com.convoscribe.diarization

import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.toFloatSamples
import com.convoscribe.data.EnrolledSpeakerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

/**
 * v2 enrollment pipeline: record a short voice sample, turn it
 * into an embedding with the
 * [com.convoscribe.engine.sherpa.SherpaSpeakerEmbedder] (or a
 * test fake), and persist the result through
 * [EnrolledSpeakerStore] so the diarization post-processing
 * step can label future recordings with the user's name.
 *
 * The use case is split across two suspend entry points so the
 * UI can drive the recording (with a progress callback) and
 * the embedding step independently:
 *
 *  * [record] collects from the [AudioSource] until enough
 *    samples are gathered to build a usable enrollment
 *    (defaults to 15 s — twice the 3 s minimum the
 *    `wespeaker_en_voxceleb_resnet34_LM` model typically
 *    wants, with margin for short prompts), then stops
 *    collecting so the mic is released. The final chunk is
 *    truncated so the returned `FloatArray` is exactly the
 *    requested length, so the embedding model never sees more
 *    audio than the user asked for.
 *  * [enroll] validates the name + sample length, runs the
 *    embedding on a CPU dispatcher (the sherpa engine is CPU-
 *    only), and saves the result under the trimmed name.
 *
 * Failures are surfaced as `IllegalArgumentException` for
 * caller mistakes (blank name, too-short sample) so the UI can
 * show a pre-translated message; engine failures propagate
 * unchanged so the caller can decide whether to retry.
 *
 * @param audioSource source of mono 16 kHz / PCM16 chunks
 *   (the recorder mic in production; a scripted fake in the
 *   unit tests).
 * @param embedderFactory builds a fresh [SpeakerEmbedder] per
 *   enrollment (the sherpa extractor retains the model in
 *   native memory, so reusing one across enrollments would
 *   just leak).
 * @param repo persistence façade for the enrolled speaker
 *   (name + embedding). The use case never touches Room
 *   directly.
 */
open class EnrollSpeakerUseCase(
    private val audioSource: com.convoscribe.audio.AudioSource,
    private val embedderFactory: SpeakerEmbedderFactory,
    private val repo: EnrolledSpeakerStore
) {

    /**
     * Collect audio from [audioSource] until [durationMs] worth of
     * mono 16 kHz samples have been captured (i.e.
     * `durationMs * [AudioSpec.SAMPLE_RATE] / 1000` samples).
     * Yields the 0..1 progress through [onProgress] so a UI
     * progress bar can mirror the recording. The returned
     * `FloatArray` is exactly the requested length — the final
     * chunk is truncated — so callers can rely on `samples.size ==
     * durationMs * AudioSpec.SAMPLE_RATE / 1000`.
     *
     * Collection stops as soon as the target is reached, which
     * cancels [audioSource]'s flow so the mic is released; the
     * source does not have to complete (the real mic never does).
     * If it completes early, the samples gathered so far are
     * returned and [enroll] rejects them when they are too short.
     *
     * Cancellation-safe: throwing [kotlinx.coroutines.CancellationException]
     * out of [onProgress] or out of the collector cancels the
     * collection and propagates as usual.
     *
     * @param durationMs how long to record, in ms. Default 15 s
     *   gives the embedding model 5x its minimum requirement
     *   even on quiet prompts.
     * @param onProgress invoked with the fraction of samples
     *   collected so far, in `0f..1f`. May be called from the
     *   collector's dispatcher.
     */
    open suspend fun record(durationMs: Long = 15_000L, onProgress: (Float) -> Unit = {}): FloatArray {
        val targetSamples = durationMs * AudioSpec.SAMPLE_RATE / 1000L
        val buffer = ArrayList<ShortArray>((targetSamples / AudioSpec.CHUNK_SAMPLES).toInt() + 1)
        var collected: Long = 0L
        // The mic never completes, so end the collection here once
        // the target is reached: `transformWhile` stops right after
        // the chunk that fills it, which cancels the source and
        // releases the mic.
        audioSource.chunks().transformWhile<ShortArray, Nothing> { chunk ->
            val remaining = targetSamples - collected
            if (chunk.size.toLong() <= remaining) {
                buffer.add(chunk)
                collected += chunk.size.toLong()
            } else {
                // The final chunk overlaps the target: take only
                // the prefix we still need so the returned array
                // is exactly the requested length.
                buffer.add(chunk.copyOf(remaining.toInt()))
                collected += remaining
            }
            onProgress((collected.toFloat() / targetSamples.toFloat()).coerceIn(0f, 1f))
            collected < targetSamples
        }.collect()
        val flat = ShortArray(collected.toInt())
        var offset = 0
        for (chunk in buffer) {
            chunk.copyInto(flat, destinationOffset = offset)
            offset += chunk.size
        }
        return flat.toFloatSamples()
    }

    /**
     * Validate the inputs, compute the embedding on
     * `Dispatchers.Default` (the sherpa extractor is CPU-only and
     * can take a few hundred ms per call), and persist the result
     * through [EnrolledSpeakerStore]. Returns once `repo.save`
     * returns, so the UI can immediately offer a "Restart" /
     * "Enroll another" choice.
     *
     * @throws IllegalArgumentException if [name] is blank or
     *   [samples] is shorter than [MIN_SAMPLES].
     */
    open suspend fun enroll(name: String, samples: FloatArray) {
        val trimmed = requireNotBlank(name)
        require(samples.size.toLong() >= MIN_SAMPLES) {
            "Need at least $MIN_SAMPLES samples (3 s of 16 kHz audio), got ${samples.size}"
        }
        val embedding = withContext(Dispatchers.Default) {
            embedderFactory.create().use { it.embed(samples) }
        }
        repo.save(trimmed, embedding)
    }

    private fun requireNotBlank(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) {
            "Speaker name must not be blank"
        }
        return trimmed
    }

    companion object {
        /**
         * Floor for an enrollable voice sample: 3 s at 16 kHz =
         * 48 000 samples. The embedding model needs roughly that
         * much speech to produce a stable vector; a shorter
         * recording makes the cosine match unreliable against
         * the diarizer's reference embeddings.
         */
        const val MIN_SAMPLES: Long = 48_000L
    }
}
