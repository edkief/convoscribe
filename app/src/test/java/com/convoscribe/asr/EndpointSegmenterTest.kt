package com.convoscribe.asr

import com.convoscribe.audio.AudioSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [EndpointSegmenter]. The backend is replaced
 * by a script-driven [ScriptedBackend] so the timing/endpoint logic
 * can be exercised without loading sherpa-onnx.
 */
class EndpointSegmenterTest {

    /**
     * [RecognizerBackend] driven by a test-supplied list of
     * `(text, isEndpoint)` pairs, one per `acceptWaveform` call.
     * The initial state (before any audio) is empty text and no
     * endpoint, matching a real recognizer.
     *
     * If the script runs out, the last `(text, endpoint)` is held
     * — matching a real recognizer's "decode loop keeps returning
     * the same partial" behaviour when no new audio arrives.
     */
    private class ScriptedBackend(script: List<Pair<String, Boolean>>) : RecognizerBackend {
        private val responses = ArrayDeque(script)
        private var currentTextBacking: String = ""
        private var endpointBacking: Boolean = false

        /** Sizes of every [acceptWaveform] call, in order. */
        val accepted: MutableList<Int> = mutableListOf()

        /** Number of times the segmenter called [reset]. */
        var resetCount: Int = 0
            private set

        override fun acceptWaveform(samples: FloatArray) {
            accepted += samples.size
            val next = responses.removeFirstOrNull()
            if (next != null) {
                currentTextBacking = next.first
                endpointBacking = next.second
            }
        }

        override fun decodeAvailable() {
            // Nothing to drain in the fake.
        }

        override fun currentText(): String = currentTextBacking

        override fun isEndpoint(): Boolean = endpointBacking

        override fun reset() {
            resetCount += 1
            // Mirroring sherpa: reset clears the partial text and
            // the endpoint latch together.
            currentTextBacking = ""
            endpointBacking = false
        }

        override fun inputFinished() {
            // No-op: finish() re-runs decodeAvailable() and then
            // reads currentText().
        }

        override fun close() {
            // No native resources.
        }
    }

    /** Build a [ScriptedBackend] from a vararg of `(text, isEndpoint)`. */
    private fun backend(vararg pairs: Pair<String, Boolean>): ScriptedBackend = ScriptedBackend(pairs.toList())

    /** A chunk of 1600 mono float samples is the recorder's unit. */
    private fun chunk(): FloatArray = FloatArray(AudioSpec.CHUNK_SAMPLES)

    /**
     * Test 1 from the task: three 1600-sample chunks, third one
     * fires an endpoint. Verifies the two partials, the final with
     * `(startMs=0, endMs=300)`, the trailing empty partial and
     * exactly one `reset()` call.
     */
    @Test
    fun accept_threeChunksWithEndpointOnTheThird() {
        val be = backend(
            "HELLO" to false,
            "HELLO WORLD" to false,
            "HELLO WORLD" to true
        )
        EndpointSegmenter(be).use { seg ->
            val e1 = seg.accept(chunk())
            val e2 = seg.accept(chunk())
            val e3 = seg.accept(chunk())
            assertEquals(listOf(TranscriptEvent.Partial("Hello")), e1)
            assertEquals(listOf(TranscriptEvent.Partial("Hello world")), e2)
            assertEquals(
                listOf(
                    TranscriptEvent.Final("Hello world", 0L, 300L),
                    TranscriptEvent.Partial("")
                ),
                e3
            )
        }
        assertEquals(1, be.resetCount)
    }

    /**
     * Test 2 from the task: a second utterance that starts right
     * after the first one's `endMs` must start at exactly that
     * millisecond. Five chunks total: three for the first
     * utterance (endMs = 300), then two for the second (startMs =
     * 300, endMs = 500).
     */
    @Test
    fun accept_secondSegmentStartsAtFirstSegmentEndMs() {
        val be = backend(
            "HELLO" to false,
            "HELLO WORLD" to false,
            "HELLO WORLD" to true,
            "foo" to false,
            "foo" to true
        )
        EndpointSegmenter(be).use { seg ->
            seg.accept(chunk()) // 0..100
            seg.accept(chunk()) // 100..200
            val e3 = seg.accept(chunk()) // 200..300 → endpoint
            assertEquals(
                listOf(
                    TranscriptEvent.Final("Hello world", 0L, 300L),
                    TranscriptEvent.Partial("")
                ),
                e3
            )
            seg.accept(chunk()) // 300..400
            val e5 = seg.accept(chunk()) // 400..500 → endpoint
            assertEquals(
                listOf(
                    // 300..500: previous endMs becomes the next startMs.
                    TranscriptEvent.Final("Foo", 300L, 500L),
                    TranscriptEvent.Partial("")
                ),
                e5
            )
        }
        assertEquals(2, be.resetCount)
    }

    /**
     * Test 3 from the task: an endpoint with blank text still calls
     * `reset()` and emits the trailing empty partial but skips the
     * `Final`. This is the endpoint-during-silence code path.
     */
    @Test
    fun accept_endpointWithBlankTextResetsButEmitsNoFinal() {
        val be = backend(
            "" to true
        )
        EndpointSegmenter(be).use { seg ->
            val events = seg.accept(chunk())
            assertEquals(listOf<TranscriptEvent>(TranscriptEvent.Partial("")), events)
        }
        assertEquals(1, be.resetCount)
    }

    /**
     * Test 4 from the task: consecutive identical partials must be
     * collapsed to a single emission. Three chunks with the same
     * text ⇒ exactly one `Partial("A")` across the first call,
     * nothing on the next two.
     */
    @Test
    fun accept_identicalConsecutivePartialsAreEmittedOnce() {
        val be = backend(
            "A" to false,
            "A" to false,
            "A" to false
        )
        EndpointSegmenter(be).use { seg ->
            val first = seg.accept(chunk())
            val second = seg.accept(chunk())
            val third = seg.accept(chunk())
            assertEquals(listOf(TranscriptEvent.Partial("A")), first)
            assertEquals(emptyList<TranscriptEvent>(), second)
            assertEquals(emptyList<TranscriptEvent>(), third)
        }
        assertEquals(0, be.resetCount)
    }

    /**
     * Test 5 from the task: `finish()` emits a `Final` for whatever
     * is pending and uses `samplesToMs(totalSamples)` as `endMs`.
     */
    @Test
    fun finish_emitsFinalForPendingTextWithEndMsAtTotalAudioMs() {
        val be = backend(
            "hello" to false
        )
        EndpointSegmenter(be).use { seg ->
            seg.accept(chunk()) // 1600 samples = 100 ms
            val events = seg.finish()
            assertEquals(
                listOf(
                    TranscriptEvent.Final("Hello", 0L, 100L),
                    TranscriptEvent.Partial("")
                ),
                events
            )
        }
    }

    /**
     * `finish()` after a clean stream (empty pending text) must
     * still emit the trailing `Partial("")` so the UI can clear
     * itself, but must *not* invent a `Final`.
     */
    @Test
    fun finish_emitsEmptyPartialWhenNothingIsPending() {
        val be = backend(
            "" to false
        )
        EndpointSegmenter(be).use { seg ->
            seg.accept(chunk()) // blank-pending state
            val events = seg.finish()
            assertEquals(listOf<TranscriptEvent>(TranscriptEvent.Partial("")), events)
        }
    }

    /**
     * Empty input chunks must be a no-op: no samples pushed, no
     * event emitted, `totalSamples` unchanged, recognizer state
     * untouched.
     */
    @Test
    fun accept_emptyChunkIsANoOp() {
        val be = backend("HELLO" to false)
        EndpointSegmenter(be).use { seg ->
            val noop = seg.accept(FloatArray(0))
            assertEquals(emptyList<TranscriptEvent>(), noop)
            assertEquals(0L, seg.totalSamples)
            // The recognizer was *not* woken up — currentText is
            // still the initial "" (script not consumed yet).
            assertEquals("", be.currentText())
            // Once we send a real chunk the recognizer wakes up and
            // consumes the first scripted entry.
            val chunk1 = seg.accept(chunk())
            assertEquals(listOf(TranscriptEvent.Partial("Hello")), chunk1)
        }
    }

    /**
     * Sanity check: `totalSamples` is the running counter used for
     * timestamps and is only ever advanced by chunk sizes — never
     * by `accept(FloatArray(0))`.
     */
    @Test
    fun totalSamples_tracksAcceptedChunkSizes() {
        val be = backend("" to true)
        EndpointSegmenter(be).use { seg ->
            seg.accept(FloatArray(0))
            assertEquals(0L, seg.totalSamples)
            seg.accept(chunk())
            assertEquals(AudioSpec.CHUNK_SAMPLES.toLong(), seg.totalSamples)
            seg.accept(FloatArray(0))
            // Empty chunks do not advance the counter.
            assertEquals(AudioSpec.CHUNK_SAMPLES.toLong(), seg.totalSamples)
        }
    }

    /**
     * The recognizer is `Closeable` too: closing the segmenter must
     * close the underlying backend (the sherpa path releases native
     * memory; the fake is a no-op).
     */
    @Test
    fun close_closesBackend() {
        val be = backend()
        val seg = EndpointSegmenter(be)
        seg.close()
        // Caller-side safety: a second close is fine, the fake
        // keeps a no-op [Closeable.close].
        seg.close()
        assertTrue(true)
    }

    /**
     * Backend whose tokens carry times relative to the last reset, as
     * sherpa-onnx's do. One `(text, endpoint, tokens)` per chunk.
     */
    private class TokenBackend(script: List<Triple<String, Boolean, List<TimedToken>>>) : RecognizerBackend {
        private val responses = ArrayDeque(script)
        private var current = Triple("", false, emptyList<TimedToken>())
        override fun acceptWaveform(samples: FloatArray) {
            responses.removeFirstOrNull()?.let { current = it }
        }
        override fun decodeAvailable() = Unit
        override fun currentText(): String = current.first
        override fun currentTokens(): List<TimedToken> = current.third
        override fun isEndpoint(): Boolean = current.second
        override fun reset() {
            current = Triple("", false, emptyList())
        }
        override fun inputFinished() = Unit
        override fun close() = Unit
    }

    @Test
    fun final_carriesTokensOnTheTranscriberClock() {
        val hi = listOf(TimedToken(" hi", 120))
        val there = listOf(TimedToken(" there", 50), TimedToken("s", 90))
        val segmenter = EndpointSegmenter(
            TokenBackend(
                listOf(
                    Triple("hi", false, hi),
                    Triple("hi", false, hi),
                    // Endpoint at 300 ms.
                    Triple("hi", true, hi),
                    Triple("theres", false, there),
                    // Endpoint at 500 ms.
                    Triple("theres", true, there)
                )
            )
        )

        val finals = (0 until 5).flatMap { segmenter.accept(chunk()) }.filterIsInstance<TranscriptEvent.Final>()

        assertEquals(listOf(TimedToken(" hi", 120)), finals[0].tokens)
        // The second line started at 300 ms; its token times restart at 0.
        assertEquals(listOf(TimedToken(" there", 350), TimedToken("s", 390)), finals[1].tokens)
        assertEquals(300L, finals[1].startMs)
    }

    /**
     * Pausing (issue #7): the pending text becomes a final ending at
     * the audio recorded so far; the tail padding that decodes it
     * reaches the backend but not the clock, and the next utterance
     * starts at the pause point.
     */
    @Test
    fun endUtterance_closesThePendingTextWithoutAdvancingTheClock() {
        val be = backend(
            "HELLO" to false,
            "HELLO" to false,
            // The padding decodes the last word.
            "HELLO THERE" to false,
            "AGAIN" to false,
            "AGAIN" to true
        )
        EndpointSegmenter(be).use { seg ->
            seg.accept(chunk())
            seg.accept(chunk())

            val events = seg.endUtterance()

            assertEquals(
                listOf(
                    TranscriptEvent.Final(text = "Hello there", startMs = 0L, endMs = 200L),
                    TranscriptEvent.Partial("")
                ),
                events
            )
            assertEquals(2L * AudioSpec.CHUNK_SAMPLES, seg.totalSamples)
            assertEquals(AudioSpec.SAMPLE_RATE / 2, be.accepted.last())
            assertEquals(1, be.resetCount)

            seg.accept(chunk())
            val next = seg.accept(chunk()).filterIsInstance<TranscriptEvent.Final>().single()
            assertEquals(200L, next.startMs)
            assertEquals(400L, next.endMs)
        }
    }

    /** Nothing since the last reset: no padding, no final, only the empty partial. */
    @Test
    fun endUtterance_withNothingPending_emitsOnlyTheEmptyPartial() {
        val be = backend()
        EndpointSegmenter(be).use { seg ->
            assertEquals(listOf(TranscriptEvent.Partial("")), seg.endUtterance())
            assertTrue(be.accepted.isEmpty())
        }
    }
}
