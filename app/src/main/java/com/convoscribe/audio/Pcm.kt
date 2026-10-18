package com.convoscribe.audio

import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Conversions between raw PCM16 sample storage (16-bit signed `Short`
 * arrays) and the `FloatArray` shape the ASR and diarization
 * pipelines expect.
 *
 * PCM16 covers `[-32768, 32767]`, so dividing by 32 768f maps the
 * range to approximately `[-1.0, 1.0]`. The 0.5 dB asymmetry
 * (`-32768 / 32768 == -1.0` but `32767 / 32768 ≈ 0.99997`) is what
 * every other ASR tool does, so we match the convention rather than
 * exact mathematical symmetry.
 */

/**
 * Convert a buffer of signed 16-bit PCM samples to floats in
 * `[-1.0, 1.0]` (each value divided by 32 768f). Returns a new
 * array; the receiver is not modified.
 */
fun ShortArray.toFloatSamples(): FloatArray {
    val out = FloatArray(size)
    for (i in indices) {
        out[i] = this[i] / 32_768f
    }
    return out
}

/**
 * Convert floats in `[-1.0, 1.0]` back to signed 16-bit PCM (times
 * 32 768, rounded; values outside the range are clipped). The inverse
 * of [toFloatSamples] for the WAV writer.
 */
fun FloatArray.toPcm16Samples(): ShortArray = ShortArray(size) { i ->
    (this[i] * 32_768f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/**
 * Loudness of this buffer for a level meter, in `0f..1f`: the RMS in
 * dBFS mapped linearly from [LEVEL_FLOOR_DB] (silence, 0) to 0 dBFS
 * (full scale, 1). The dB scale makes normal speech, around -30 to
 * -15 dBFS, fill half to three quarters of the meter. An empty or
 * silent buffer is 0.
 */
fun ShortArray.inputLevel(): Float {
    if (isEmpty()) return 0f
    var sum = 0.0
    for (sample in this) {
        val v = sample / 32_768.0
        sum += v * v
    }
    val rms = sqrt(sum / size)
    if (rms <= 0.0) return 0f
    val db = 20.0 * log10(rms)
    return ((db - LEVEL_FLOOR_DB) / -LEVEL_FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
}

/** dBFS shown as an empty level meter; anything quieter is silence. */
const val LEVEL_FLOOR_DB = -60.0

/**
 * Convert a sample count to milliseconds at [sampleRate] (defaults
 * to [AudioSpec.SAMPLE_RATE]). Used to translate streaming ASR
 * timestamps and `AudioRecord` chunk counts into the millisecond
 * range the UI and the diarization segments share.
 */
fun samplesToMs(samples: Long, sampleRate: Int = AudioSpec.SAMPLE_RATE): Long = samples * 1000L / sampleRate.toLong()
