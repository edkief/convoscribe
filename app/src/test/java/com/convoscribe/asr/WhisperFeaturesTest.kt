package com.convoscribe.asr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WhisperFeatures] against values from an independent pure-Python
 * reference of OpenAI's `log_mel_spectrogram` with librosa's Slaney mel
 * filters (issue #159), on a 1 s chirp from 200 Hz to 2 kHz at half
 * amplitude, padded to 30 s.
 */
class WhisperFeaturesTest {

    private val chirp = FloatArray(16_000) { n ->
        val t = n / 16_000.0
        (0.5 * sin(2 * PI * (200 * t + 900 * t * t))).toFloat()
    }

    private val features by lazy { WhisperFeatures.compute(chirp) }

    private fun at(mel: Int, frame: Int) = features[mel * WhisperFeatures.FRAMES + frame]

    @Test
    fun shapeIs128By3000() {
        assertEquals(128 * 3000, features.size)
    }

    @Test
    fun matchesTheReference() {
        val expected = listOf(
            Triple(0, 0, 1.0922555030582048),
            Triple(5, 1, 0.9901723893815055),
            Triple(10, 10, 0.43212666652000276),
            Triple(20, 25, 0.2256827531500225),
            Triple(40, 50, 0.48958593929380567),
            Triple(60, 75, 1.429325298169972),
            Triple(80, 90, -0.5004188728072585),
            Triple(100, 99, -0.21882061447019807),
            Triple(127, 100, 0.10720975606134708),
            Triple(30, 101, 0.04428280896389192)
        )
        for ((mel, frame, value) in expected) {
            assertEquals("mel $mel frame $frame", value, at(mel, frame).toDouble(), 2e-3)
        }
    }

    @Test
    fun silenceAfterTheAudio_isFlooredEightBelowTheMaximum() {
        // Reference maximum log10 power: 1.99832…; the floor is 8 below it.
        val floor = ((1.9983245087709658 - 8) + 4) / 4
        for ((mel, frame) in listOf(50 to 102, 64 to 2999, 3 to 40, 15 to 60, 25 to 80)) {
            assertEquals("mel $mel frame $frame", floor, at(mel, frame).toDouble(), 2e-3)
        }
        assertTrue(features.all { it >= floor - 1e-3 })
    }

    @Test
    fun allSilence_isMinusOneAndAHalf() {
        // log10(1e-10) = -10 everywhere, so the floor changes nothing.
        val silent = WhisperFeatures.compute(FloatArray(8_000))
        assertTrue(silent.all { abs(it + 1.5f) < 1e-6f })
    }

    @Test
    fun longerThan30s_isCut() {
        val long = FloatArray(WhisperFeatures.SAMPLES + 16_000) { if (it < 16_000) chirp[it] else 0f }
        val cut = WhisperFeatures.compute(long)
        assertEquals(features.size, cut.size)
        assertEquals(at(60, 75), cut[60 * WhisperFeatures.FRAMES + 75], 1e-6f)
    }

    @Test
    fun melFilters_matchLibrosa() {
        val w = WhisperFeatures.melFilters
        assertEquals(128, w.size)
        assertEquals(201, w[0].size)
        assertEquals(0.012373986332636965, w[0][1].toDouble(), 1e-7)
        assertEquals(0.0, w[10][5].toDouble(), 0.0)
        assertEquals(0.02484120032574144, w[64].sum().toDouble(), 1e-6)
    }
}
