package com.convoscribe.asr

import com.convoscribe.audio.WavReader
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

class FileTranscriberTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A WAV of [ms] milliseconds; sample `i` holds `i % 1000` so chunk order is checkable. */
    private fun wav(ms: Int): File {
        val file = tmp.newFile("in.wav")
        WavWriter(file).use { w -> w.write(ShortArray(ms * 16) { (it % 1000).toShort() }) }
        return file
    }

    /** Emits one final per second of audio, timed on its own sample clock, plus one on finish. */
    private class SecondTranscriber : StreamingTranscriber {
        var samples = 0L
        var lastStart = 0L
        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            this.samples += samples.size
            val ms = this.samples / 16
            return if (ms - lastStart >= 1000) {
                val event = TranscriptEvent.Final("s$ms", lastStart, ms)
                lastStart = ms
                listOf(TranscriptEvent.Partial(""), event)
            } else {
                listOf(TranscriptEvent.Partial("…"))
            }
        }
        override fun finish(): List<TranscriptEvent> =
            listOf(TranscriptEvent.Final("tail", lastStart, samples / 16), TranscriptEvent.Final(" ", 0, 0))
        override fun close() = Unit
    }

    @Test
    fun readChunks16k_streamsTheWholeFileInOrder() {
        val file = wav(ms = 250)
        val chunks = mutableListOf<FloatArray>()
        WavReader.readChunks16k(file, chunkSamples = 1600) { chunks += it }
        assertEquals(listOf(1600, 1600, 800), chunks.map { it.size })
        val joined = chunks.flatMap { it.toList() }
        assertEquals(WavReader.readMono16k(file).toList(), joined)
        assertEquals(4000L, WavReader.samples16k(file))
    }

    @Test
    fun transcribe_returnsNonBlankFinalsWithFileTimesAndReportsProgress() = runTest {
        val file = wav(ms = 2_500)
        val progress = mutableListOf<Float>()
        val finals = FileTranscriber.transcribe(file, SecondTranscriber(), chunkSamples = 1600) { progress += it }

        assertEquals(listOf("s1000", "s2000", "tail"), finals.map { it.text })
        assertEquals(listOf(0L to 1000L, 1000L to 2000L, 2000L to 2500L), finals.map { it.startMs to it.endMs })
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun transcribe_stopsWhenCancelled() = runTest {
        val file = wav(ms = 5_000)
        val transcriber = SecondTranscriber()
        val job = Job()
        try {
            withContext(job) {
                FileTranscriber.transcribe(file, transcriber, chunkSamples = 1600) { p -> if (p > 0.2f) job.cancel() }
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(transcriber.samples < 5_000L * 16)
    }
}
