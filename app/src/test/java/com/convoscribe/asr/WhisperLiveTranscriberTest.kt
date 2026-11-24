package com.convoscribe.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLiveTranscriberTest {

    /** Treats everything as speech and closes a segment every [segmentMs] ms; the rest on flush. */
    private class EveryN(private val segmentMs: Int) : VoiceDetector {
        private var pending = FloatArray(0)
        private var start = 0L
        private val closedSpans = mutableListOf<SpeechSpan>()
        var closed = false

        override fun accept(samples: FloatArray) {
            pending += samples
            val n = segmentMs * 16
            while (pending.size >= n) {
                closedSpans += SpeechSpan(start, pending.copyOfRange(0, n))
                pending = pending.copyOfRange(n, pending.size)
                start += n
            }
        }

        override fun flush() {
            if (pending.isNotEmpty()) closedSpans += SpeechSpan(start, pending)
            start += pending.size
            pending = FloatArray(0)
        }

        override fun drain(): List<SpeechSpan> = closedSpans.toList().also { closedSpans.clear() }

        override fun close() {
            closed = true
        }
    }

    /** Answers "words <n>" for a segment of n samples, after [gate] opens. */
    private class FakeDecoder : WhisperDecoder {
        @Volatile var gate: CountDownLatch = CountDownLatch(0)

        @Volatile var failure: Throwable? = null

        @Volatile var blankFor: Int = -1

        @Volatile var decoding = false

        @Volatile var closedWhileDecoding = false
        val sizes = mutableListOf<Int>()
        var closed = false

        override val info = EngineInfo("cpu", "whisper")

        override fun decode(samples: FloatArray): String {
            decoding = true
            try {
                gate.await(5, TimeUnit.SECONDS)
                failure?.let { throw it }
                synchronized(sizes) { sizes += samples.size }
                return if (samples.size == blankFor) "" else "words ${samples.size}"
            } finally {
                decoding = false
            }
        }

        override fun close() {
            if (decoding) closedWhileDecoding = true
            closed = true
        }
    }

    private val decoder = FakeDecoder()
    private val detectors = mutableListOf<EveryN>()
    private var transcriber = WhisperLiveTranscriber(decoder, { EveryN(1_000).also(detectors::add) })

    @After
    fun tearDown() {
        decoder.gate = CountDownLatch(0)
        transcriber.close()
    }

    private fun ms(n: Int) = FloatArray(n * 16)

    private fun List<TranscriptEvent>.finals() = filterIsInstance<TranscriptEvent.Final>()

    @Test
    fun accept_doesNotWaitForWhisper() {
        decoder.gate = CountDownLatch(1)

        // Ten segments' worth while Whisper is stuck on the first.
        val events = (1..10).flatMap { transcriber.accept(ms(1_000)) }

        assertTrue(events.isEmpty())
        assertEquals(10, transcriber.status.value.pending)
        decoder.gate.countDown()
        val rest = transcriber.finish().finals()
        assertEquals(10, rest.size)
        assertEquals(0, transcriber.status.value.pending)
    }

    @Test
    fun lines_comeBackInOrder_withTheirTimes_andSpreadWordTimes() {
        transcriber.accept(ms(2_500))
        val lines = transcriber.finish()

        val finals = lines.finals()
        assertEquals(listOf(0L to 1_000L, 1_000L to 2_000L, 2_000L to 2_500L), finals.map { it.startMs to it.endMs })
        assertEquals("words 8000", finals.last().text)
        assertEquals(listOf(2_000L, 2_250L), finals.last().tokens.map { it.ms })
        assertEquals(TranscriptEvent.Partial(""), lines.last())
    }

    @Test
    fun decodedLines_arriveWithTheNextAccept() {
        transcriber.accept(ms(1_000))
        // Wait for the worker, then any accept hands the line over.
        val deadline = System.currentTimeMillis() + 5_000
        var events = emptyList<TranscriptEvent>()
        while (events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            events = transcriber.accept(ms(10))
        }
        assertEquals("words 16000", events.finals().single().text)
    }

    @Test
    fun pause_closesTheSegment_andTheNextRunKeepsTheRecordingClock() {
        transcriber.accept(ms(1_500))
        val beforePause = transcriber.endUtterance().finals()
        transcriber.accept(ms(1_200))
        val afterResume = transcriber.finish().finals()

        assertEquals(listOf(0L to 1_000L, 1_000L to 1_500L), beforePause.map { it.startMs to it.endMs })
        assertEquals(listOf(1_500L to 2_500L, 2_500L to 2_700L), afterResume.map { it.startMs to it.endMs })
        assertEquals(2, detectors.size)
        assertTrue(detectors.first().closed)
    }

    @Test
    fun segmentsLongerThanTheMax_areCut() {
        transcriber.close()
        transcriber = WhisperLiveTranscriber(decoder, { EveryN(10_000) }, maxSamples = 4 * 16_000)

        transcriber.accept(ms(10_000))
        val finals = transcriber.finish().finals()

        assertEquals(3, finals.size)
        assertTrue(decoder.sizes.all { it <= 4 * 16_000 })
        assertEquals(10_000L, finals.last().endMs)
    }

    @Test
    fun blankDecodes_makeNoLine() {
        decoder.blankFor = 16_000
        transcriber.accept(ms(1_000))
        transcriber.accept(ms(300))
        val finals = transcriber.finish().finals()
        assertEquals(listOf("words 4800"), finals.map { it.text })
    }

    @Test
    fun failure_isPublished_andAudioIsStillAccepted() {
        decoder.failure = IllegalStateException("out of memory")

        transcriber.accept(ms(2_000))
        val finals = transcriber.finish().finals()

        assertTrue(finals.isEmpty())
        assertEquals("out of memory", transcriber.status.value.failure)
        assertEquals(0, transcriber.status.value.pending)
        // More audio after a failure is taken and ignored.
        assertTrue(transcriber.accept(ms(3_000)).isEmpty())
        assertEquals(0, transcriber.status.value.pending)
    }

    @Test
    fun noFailure_isNull() {
        transcriber.accept(ms(1_000))
        transcriber.finish()
        assertNull(transcriber.status.value.failure)
    }

    @Test
    fun close_waitsForTheSegmentBeingDecoded_beforeReleasingWhisper() {
        decoder.gate = CountDownLatch(1)
        transcriber.accept(ms(1_000))
        while (!decoder.decoding) Thread.sleep(1)

        val closer = Thread { transcriber.close() }.apply { start() }
        Thread.sleep(50)
        assertFalse(decoder.closed)
        decoder.gate.countDown()
        closer.join(5_000)

        assertTrue(decoder.closed)
        assertFalse(decoder.closedWhileDecoding)
    }
}
