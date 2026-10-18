package com.convoscribe.asr

import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.samplesToMs
import java.io.Closeable

/**
 * Engine-agnostic streaming ASR façade. The home screen consumes
 * audio chunks, gets back a small batch of [TranscriptEvent]s to
 * render/append and stops calling [accept] once [finish] has been
 * invoked on the recording boundary.
 *
 * One instance owns one utterance sequence: opening a new
 * recognizer for every chunk is pointless and re-using one across
 * recordings would mix samples from different sessions.
 */
interface StreamingTranscriber : Closeable {

    /**
     * Push one mono 16 kHz float PCM chunk and return the events
     * that settling this chunk produced. The list is small
     * (usually 0 or 1, at most 2 on an endpoint) — the recorder
     * never gets more than a few events per chunk.
     */
    fun accept(samples: FloatArray): List<TranscriptEvent>

    /**
     * Tell the recognizer no more audio will arrive. Returns the
     * final batch of events: a [TranscriptEvent.Final] if any text
     * was pending, always followed by a `Partial("")` so the UI
     * can clear its "listening" placeholder.
     */
    fun finish(): List<TranscriptEvent>

    /**
     * Close the current utterance without ending the stream: used
     * when a recording is paused (issue #7), so the words before the
     * pause become their own segment and the next [accept] starts a
     * new one at the pause point. Unlike [finish], more audio may
     * follow. Returns a [TranscriptEvent.Final] if any text was
     * pending, then a `Partial("")`. The default does nothing, for
     * transcribers that have no pending state.
     */
    fun endUtterance(): List<TranscriptEvent> = emptyList()
}

/**
 * Pure-Kotlin adapter that turns a [RecognizerBackend] into a
 * [StreamingTranscriber] by adding the timing and event-decision
 * logic sherpa would otherwise leave to the caller:
 *
 *  * [totalSamples] is the running sample count across every
 *    [accept] call in this segmenter's lifetime. It drives the
 *    end timestamps of [TranscriptEvent.Final].
 *  * [segmentStartSample] is the audio-sample index at which the
 *    current utterance started (0 for the first utterance; the
 *    previous segment's `totalSamples` after every endpoint).
 *  * Consecutive identical [TranscriptEvent.Partial]s are
 *    suppressed — recognizers often emit the same text for many
 *    chunks before a new token arrives.
 *  * An endpoint with a blank text still resets the backend so
 *    silence doesn't carry one half-utterance into the next.
 *
 * The class is engine-agnostic on purpose: tests inject a
 * `FakeBackend` (a queue of `(text, isEndpoint)` per
 * `acceptWaveform`) and exercise every branch without loading
 * sherpa-onnx.
 */
class EndpointSegmenter(
    private val backend: RecognizerBackend,
    private val sampleRate: Int = AudioSpec.SAMPLE_RATE
) : StreamingTranscriber {

    /**
     * Total samples pushed through [accept] since this segmenter
     * was created. Exposed (instead of duplicated in the test) so
     * timing-related tests can assert the exact counter the
     * implementation is using.
     */
    var totalSamples: Long = 0L
        private set

    /**
     * Audio-sample index of the start of the current utterance.
     * Reset to [totalSamples] every time the backend reports an
     * endpoint and the segment emits a [TranscriptEvent.Final].
     */
    private var segmentStartSample: Long = 0L

    /**
     * Last text emitted as `Partial`. Used to suppress
     * consecutive duplicates. Starts empty so the very first
     * partial after a non-empty `currentText()` is always emitted.
     */
    private var lastPartialText: String = ""

    override fun accept(samples: FloatArray): List<TranscriptEvent> {
        // Empty input is a no-op: no samples pushed, no decoder
        // wakeup, no event repeated. A real recorder never hands
        // us a 0-length chunk; treating it as an explicit skip
        // keeps the upstream code free of size guards.
        if (samples.isEmpty()) return emptyList()
        totalSamples += samples.size
        backend.acceptWaveform(samples)
        backend.decodeAvailable()
        val text = TextNormalizer.normalize(backend.currentText())
        val events = mutableListOf<TranscriptEvent>()
        if (backend.isEndpoint()) {
            if (text.isNotBlank()) {
                events += finalEvent(text)
            }
            backend.reset()
            segmentStartSample = totalSamples
            events += TranscriptEvent.Partial("")
            // After a reset the backend's partial is gone; remember
            // the empty placeholder we just emitted so we don't
            // immediately re-emit it on the very next chunk.
            lastPartialText = ""
        } else if (text != lastPartialText) {
            events += TranscriptEvent.Partial(text)
            lastPartialText = text
        }
        return events
    }

    override fun finish(): List<TranscriptEvent> {
        backend.inputFinished()
        backend.decodeAvailable()
        val text = TextNormalizer.normalize(backend.currentText())
        val events = mutableListOf<TranscriptEvent>()
        if (text.isNotBlank()) {
            events += finalEvent(text)
        }
        // Always send an empty partial last so the UI can clear any
        // placeholder it had on screen.
        events += TranscriptEvent.Partial("")
        lastPartialText = ""
        return events
    }

    /**
     * Push [TAIL_PADDING_MS] of silence to the backend only, so the
     * last word before a pause is decoded, then emit the pending
     * text and reset. The padding is not counted in [totalSamples]:
     * it never reaches the WAV, so the transcriber's clock stays on
     * the recorded audio. Nothing is padded when no audio arrived
     * since the last reset.
     */
    override fun endUtterance(): List<TranscriptEvent> {
        if (totalSamples > segmentStartSample) {
            backend.acceptWaveform(FloatArray((sampleRate * TAIL_PADDING_MS / 1_000L).toInt()))
            backend.decodeAvailable()
        }
        val text = TextNormalizer.normalize(backend.currentText())
        val events = mutableListOf<TranscriptEvent>()
        if (text.isNotBlank()) {
            events += finalEvent(text)
        }
        backend.reset()
        segmentStartSample = totalSamples
        events += TranscriptEvent.Partial("")
        lastPartialText = ""
        return events
    }

    override fun close() {
        backend.close()
    }

    /**
     * The closed utterance, with the backend's token times (relative
     * to the last reset) moved onto this transcriber's clock.
     */
    private fun finalEvent(text: String): TranscriptEvent.Final {
        val startMs = samplesToMs(segmentStartSample, sampleRate)
        return TranscriptEvent.Final(
            text = text,
            startMs = startMs,
            endMs = samplesToMs(totalSamples, sampleRate),
            tokens = backend.currentTokens().map { it.copy(ms = it.ms + startMs) }
        )
    }

    private companion object {
        /** Silence fed after the last chunk on [endUtterance]. */
        const val TAIL_PADDING_MS = 500L
    }
}
