package com.convoscribe.asr

import com.convoscribe.audio.WavWriter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechChunkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wav(ms: Int): File {
        val file = tmp.newFile("in.wav")
        WavWriter(file).use { w -> w.write(ShortArray(ms * 16) { 1000 }) }
        return file
    }

    /**
     * Treats the whole input as speech and closes a segment every
     * [segmentMs] milliseconds (and the rest on flush), like a VAD
     * hearing someone talk with regular pauses.
     */
    private class EveryN(private val segmentMs: Int) : VoiceDetector {
        private var pending = FloatArray(0)
        private var start = 0L
        private val closed = mutableListOf<SpeechSpan>()
        var closedCalled = false

        override fun accept(samples: FloatArray) {
            pending += samples
            val n = segmentMs * 16
            while (pending.size >= n) {
                closed += SpeechSpan(start, pending.copyOfRange(0, n))
                pending = pending.copyOfRange(n, pending.size)
                start += n
            }
        }

        override fun flush() {
            if (pending.isNotEmpty()) closed += SpeechSpan(start, pending)
            start += pending.size
            pending = FloatArray(0)
        }

        override fun drain(): List<SpeechSpan> = closed.toList().also { closed.clear() }

        override fun close() {
            closedCalled = true
        }
    }

    @Test
    fun spans_comeInOrderWithTimesFromTheStartOfTheFile() = runTest {
        val spans = mutableListOf<SpeechSpan>()
        SpeechChunker.chunk(wav(2_500), EveryN(1_000), chunkSamples = 1_600) { spans += it }
        assertEquals(listOf(0L to 1_000L, 1_000L to 2_000L, 2_000L to 2_500L), spans.map { it.startMs to it.endMs })
    }

    @Test
    fun longSpan_isCutIntoEqualPiecesWithinTheCap() {
        val span = SpeechSpan(16_000, FloatArray(50 * 16_000))
        val pieces = SpeechChunker.split(span, maxSamples = 28 * 16_000)
        assertEquals(2, pieces.size)
        assertEquals(listOf(1_000L, 26_000L), pieces.map { it.startMs })
        assertEquals(51_000L, pieces.last().endMs)
        assertTrue(pieces.all { it.samples.size <= 28 * 16_000 })
    }

    @Test
    fun shortSpan_isKeptAsItIs() {
        val span = SpeechSpan(0, FloatArray(100))
        assertEquals(listOf(span), SpeechChunker.split(span, maxSamples = 100))
    }

    @Test
    fun progress_endsAtOne() = runTest {
        val progress = mutableListOf<Float>()
        SpeechChunker.chunk(wav(1_000), EveryN(400), chunkSamples = 1_600, onProgress = { progress += it }) {}
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun cancelling_stopsBetweenChunks() = runTest {
        val job = Job()
        var spans = 0
        try {
            withContext(job) {
                SpeechChunker.chunk(wav(5_000), EveryN(100), chunkSamples = 1_600) {
                    spans++
                    job.cancel()
                }
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, spans)
    }
}
