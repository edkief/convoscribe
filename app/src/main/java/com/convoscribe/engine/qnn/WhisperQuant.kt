package com.convoscribe.engine.qnn

import kotlin.math.roundToInt

/**
 * Quantization of the w8a16 Whisper NPU build's inputs and outputs
 * (issue #159), from each published zip's `metadata.json`; the values are
 * the same for every chip. A 16-bit value `q` stands for
 * `(q − zero) × scale`, read as unsigned.
 *
 * The self and cross attention caches (uint8) never leave the NPU's
 * format: the encoder's cross caches feed the decoder as they are, and
 * each decoder step's self caches feed the next with the same scales.
 * Only an empty cache is written here: every byte [CACHE_ZERO].
 */
internal object WhisperQuant {

    /** `input_features`, the log-mel spectrogram. */
    const val FEATURES_SCALE = 4.746108970721252e-05f
    const val FEATURES_ZERO = 31605

    /** `attention_mask`: `0` → 65535 (open), [com.convoscribe.asr.WhisperGreedyDecoder.MASK_NEG] → 0. */
    const val MASK_SCALE = 0.0015259021893143654f
    const val MASK_ZERO = 65535

    /** `logits`. */
    const val LOGITS_SCALE = 0.0007861469639465213f
    const val LOGITS_ZERO = 23109

    /** The byte that stands for 0.0 in every uint8 cache. */
    const val CACHE_ZERO: Byte = 128.toByte()

    /** [value] as an unsigned 16-bit [scale]/[zero] quantity, clamped to its range. */
    fun quantize(value: Float, scale: Float, zero: Int): Short =
        ((value / scale).roundToInt() + zero).coerceIn(0, 0xFFFF).toShort()

    /** The value an unsigned 16-bit [q] stands for. */
    fun dequantize(q: Short, scale: Float, zero: Int): Float = ((q.toInt() and 0xFFFF) - zero) * scale
}
