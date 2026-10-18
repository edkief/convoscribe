package com.convoscribe.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downmix and resampling of decoded audio to the app's 16 kHz mono
 * (issue #3): the pure part of the import path, which runs without
 * the platform codecs.
 */
class PcmConverterTest {

    private fun tone(hz: Double, rate: Int, seconds: Double, amplitude: Float = 0.5f): FloatArray =
        FloatArray((rate * seconds).toInt()) { i -> amplitude * sin(2 * PI * hz * i / rate).toFloat() }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from))
    }

    /** Amplitude of the [hz] component of [samples] at [rate] (single-bin DFT). */
    private fun magnitude(samples: FloatArray, hz: Double, rate: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in samples.indices) {
            val angle = 2 * PI * hz * i / rate
            re += samples[i] * kotlin.math.cos(angle)
            im -= samples[i] * sin(angle)
        }
        return 2 * sqrt(re * re + im * im) / samples.size
    }

    private fun convertAll(converter: PcmConverter, blocks: List<PcmBlock>): FloatArray =
        blocks.map { converter.convert(it) }.reduce(FloatArray::plus) + converter.flush()

    @Test
    fun downmix_averagesChannels() {
        val stereo = floatArrayOf(0.5f, 0.5f, 1f, -1f, 0.2f, 0.4f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.3f), PcmConverter.downmix(stereo, 2), 1e-6f)
    }

    @Test
    fun downmix_monoIsUnchanged() {
        val mono = floatArrayOf(0.1f, 0.2f)
        assertArrayEquals(mono, PcmConverter.downmix(mono, 1), 0f)
    }

    @Test
    fun convert_16kMonoPassesThroughUnchanged() {
        val input = tone(440.0, 16_000, 0.5)
        val out = convertAll(PcmConverter(), listOf(PcmBlock(input, 1, 16_000)))
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun convert_stereo44kToneKeepsItsPitchAndLevelAt16k() {
        val left = tone(1_000.0, 44_100, 1.0)
        val interleaved = FloatArray(left.size * 2) { left[it / 2] }
        val out = convertAll(PcmConverter(), listOf(PcmBlock(interleaved, 2, 44_100)))

        assertEquals(16_000, out.size)
        // Away from the edges the tone keeps its amplitude at 1 kHz.
        val middle = out.copyOfRange(1_000, 15_000)
        assertEquals(0.5, magnitude(middle, 1_000.0, 16_000), 0.02)
        assertEquals(0.5 / sqrt(2.0), rms(middle), 0.01)
    }

    @Test
    fun convert_upsamples8kTo16k() {
        val input = tone(500.0, 8_000, 1.0)
        val out = convertAll(PcmConverter(), listOf(PcmBlock(input, 1, 8_000)))
        assertEquals(16_000, out.size)
        assertEquals(0.5, magnitude(out.copyOfRange(1_000, 15_000), 500.0, 16_000), 0.02)
    }

    @Test
    fun convert_filtersOutToneAboveTheNewNyquist() {
        // 12 kHz at 48 kHz would fold to 4 kHz with plain decimation.
        val out = convertAll(PcmConverter(), listOf(PcmBlock(tone(12_000.0, 48_000, 1.0), 1, 48_000)))
        assertEquals(16_000, out.size)
        val middle = out.copyOfRange(1_000, 15_000)
        assertTrue("alias level ${rms(middle)}", rms(middle) < 0.005)
        assertTrue(magnitude(middle, 4_000.0, 16_000) < 0.005)
    }

    @Test
    fun convert_blockSizesDoNotChangeTheResult() {
        val input = tone(700.0, 48_000, 0.7) + tone(300.0, 48_000, 0.3)
        val whole = convertAll(PcmConverter(), listOf(PcmBlock(input, 1, 48_000)))
        val blocks = mutableListOf<PcmBlock>()
        var start = 0
        var size = 1
        while (start < input.size) {
            val end = minOf(start + size, input.size)
            blocks += PcmBlock(input.copyOfRange(start, end), 1, 48_000)
            start = end
            size = size * 3 % 4_097 + 1
        }
        val streamed = convertAll(PcmConverter(), blocks)
        assertEquals(whole.size, streamed.size)
        for (i in whole.indices) assertTrue("sample $i", abs(whole[i] - streamed[i]) < 1e-5f)
    }

    @Test
    fun resampler_outputLengthIsFloorOfTheRatio() {
        for ((from, n) in listOf(44_100 to 44_101, 22_050 to 999, 11_025 to 7, 48_000 to 2, 32_000 to 1)) {
            val resampler = StreamingResampler(from, 16_000)
            val out = resampler.process(FloatArray(n) { 0.1f }) + resampler.flush()
            assertEquals("from $from n $n", n.toLong() * 16_000 / from, out.size.toLong())
        }
    }

    @Test
    fun resampler_keepsADcLevel() {
        val resampler = StreamingResampler(44_100, 16_000)
        val out = resampler.process(FloatArray(44_100) { 0.25f }) + resampler.flush()
        for (i in 200 until out.size - 200) assertEquals(0.25f, out[i], 1e-3f)
    }

    @Test
    fun convert_rateChangeMidStreamFlushesTheOldRate() {
        val converter = PcmConverter()
        val a = converter.convert(PcmBlock(FloatArray(48_000) { 0.1f }, 1, 48_000))
        val b = converter.convert(PcmBlock(FloatArray(8_000) { 0.1f }, 1, 8_000))
        val total = a.size + b.size + converter.flush().size
        assertEquals(16_000 + 16_000, total)
    }
}
