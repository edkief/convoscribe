package com.convoscribe.engine.qnn

import com.convoscribe.asr.WhisperGreedyDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperQuantTest {

    private fun mask(v: Float) =
        WhisperQuant.quantize(v, WhisperQuant.MASK_SCALE, WhisperQuant.MASK_ZERO).toInt() and 0xFFFF

    @Test
    fun mask_openIsTheTopAndClosedIsTheBottom() {
        assertEquals(65535, mask(0f))
        assertEquals(0, mask(WhisperGreedyDecoder.MASK_NEG))
    }

    @Test
    fun features_coverWhispersRange() {
        // Silence is -1.5 after Whisper's normalisation: the bottom of the range.
        val silence = WhisperQuant.quantize(-1.5f, WhisperQuant.FEATURES_SCALE, WhisperQuant.FEATURES_ZERO)
        assertEquals(0, silence.toInt() and 0xFFFF)
        for (v in listOf(-1.2f, -0.3f, 0f, 0.7f, 1.5f)) {
            val q = WhisperQuant.quantize(v, WhisperQuant.FEATURES_SCALE, WhisperQuant.FEATURES_ZERO)
            assertEquals(v, WhisperQuant.dequantize(q, WhisperQuant.FEATURES_SCALE, WhisperQuant.FEATURES_ZERO), 3e-5f)
        }
    }

    @Test
    fun valuesOutsideTheRange_areClamped() {
        assertEquals(0, WhisperQuant.quantize(-10f, WhisperQuant.FEATURES_SCALE, WhisperQuant.FEATURES_ZERO).toInt())
        assertEquals(-1, WhisperQuant.quantize(10f, WhisperQuant.FEATURES_SCALE, WhisperQuant.FEATURES_ZERO).toInt())
    }

    @Test
    fun logits_readUnsigned() {
        // 0xFFFF is the largest logit, not -1.
        val top = WhisperQuant.dequantize(0xFFFF.toShort(), WhisperQuant.LOGITS_SCALE, WhisperQuant.LOGITS_ZERO)
        assertEquals((65535 - 23109) * WhisperQuant.LOGITS_SCALE, top, 1e-4f)
        val zero = WhisperQuant.dequantize(23109.toShort(), WhisperQuant.LOGITS_SCALE, WhisperQuant.LOGITS_ZERO)
        assertEquals(0f, zero, 0f)
    }
}
