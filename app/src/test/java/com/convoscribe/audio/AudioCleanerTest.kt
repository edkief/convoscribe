package com.convoscribe.audio

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [aligned] and [AudioCleaning.cleanFile] (issue #37). */
class AudioCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A cleaner that halves every sample and works in [hop]-sample
     * frames, holding back what does not fill a frame, like the
     * sherpa denoiser. [flushExtra] samples of padding come out of
     * [flush] past the input, or are missing when negative.
     */
    private class HopCleaner(private val hop: Int, private val flushExtra: Int = 0) : StreamingCleaner {
        private val pending = mutableListOf<Float>()
        var closed = false

        override fun process(samples: FloatArray): FloatArray {
            pending += samples.toList()
            val whole = pending.size / hop * hop
            val out = pending.take(whole).map { it / 2 }
            repeat(whole) { pending.removeAt(0) }
            return out.toFloatArray()
        }

        override fun flush(): FloatArray {
            val out = pending.map { it / 2 } + List(flushExtra.coerceAtLeast(0)) { 9f }
            pending.clear()
            return out.dropLast((-flushExtra).coerceAtLeast(0)).toFloatArray()
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun aligned_outputMatchesInputLengthAndOrder() {
        val cleaner = HopCleaner(hop = 256).aligned()
        val input = FloatArray(1_000) { it.toFloat() }
        val out = cleaner.process(input.copyOfRange(0, 300)) +
            cleaner.process(input.copyOfRange(300, 1_000)) +
            cleaner.flush()
        assertArrayEquals(FloatArray(1_000) { it / 2f }, out, 0f)
    }

    @Test
    fun aligned_trimsSurplusAndPadsShortfall() {
        val surplus = HopCleaner(hop = 4, flushExtra = 5).aligned()
        assertEquals(10, (surplus.process(FloatArray(10) { 1f }) + surplus.flush()).size)

        val short = HopCleaner(hop = 4, flushExtra = -1).aligned()
        val out = short.process(FloatArray(10) { 1f }) + short.flush()
        assertEquals(10, out.size)
        assertEquals(0f, out.last(), 0f)
    }

    @Test
    fun aligned_countsAgainAfterFlush() {
        val cleaner = HopCleaner(hop = 4, flushExtra = 3).aligned()
        assertEquals(6, (cleaner.process(FloatArray(6)) + cleaner.flush()).size)
        assertEquals(5, (cleaner.process(FloatArray(5)) + cleaner.flush()).size)
    }

    @Test
    fun aligned_closesTheEngine() {
        val engine = HopCleaner(hop = 4)
        engine.aligned().close()
        assertEquals(true, engine.closed)
    }

    @Test
    fun cleanFile_writesAnAlignedWavAndReportsProgress() = runTest {
        val source = File(tmp.root, "in.wav")
        val samples = ShortArray(5_000) { ((it % 200) * 100).toShort() }
        WavWriter(source).use { it.write(samples) }
        val target = File(tmp.root, "out.wav")
        val progress = mutableListOf<Float>()

        AudioCleaning.cleanFile(source, target, HopCleaner(hop = 256), chunkSamples = 1_600) { progress += it }

        val cleaned = WavReader.readMono16k(target)
        assertEquals(samples.size, cleaned.size)
        assertEquals(samples[399] / 2 / 32_768f, cleaned[399], 1e-4f)
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress, progress.sorted())
    }

    @Test
    fun forRecognizer_blendsHalfAndHalf() {
        assertArrayEquals(
            floatArrayOf(0.75f, 0f),
            AudioCleaning.forRecognizer(floatArrayOf(0.5f, 0f), floatArrayOf(1f, 0f)),
            1e-6f
        )
    }

    @Test
    fun writeRecognizerMix_streamsTheBlendOfBothFiles() = runTest {
        val original = File(tmp.root, "o.wav").apply { WavWriter(this).use { it.write(ShortArray(4_000) { 8_192 }) } }
        val cleaned = File(tmp.root, "c.wav").apply { WavWriter(this).use { it.write(ShortArray(4_000) { 4_096 }) } }
        val mix = File(tmp.root, "m.wav")

        AudioCleaning.writeRecognizerMix(original, cleaned, mix, chunkSamples = 1_600)

        val out = WavReader.readMono16k(mix)
        assertEquals(4_000, out.size)
        assertEquals(0.1875f, out[3_999], 1e-4f)
    }
}
