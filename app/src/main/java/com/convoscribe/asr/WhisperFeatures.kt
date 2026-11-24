package com.convoscribe.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper's log-mel input features in plain Kotlin (issue #159), for the
 * NPU build, which takes them as a tensor (sherpa-onnx computes its own
 * for the CPU path). Matches OpenAI's `log_mel_spectrogram` and Hugging
 * Face's `WhisperFeatureExtractor` for Large-v3:
 *
 *  - the audio (16 kHz mono) is zero-padded or cut to 30 s;
 *  - 400-point periodic Hann STFT, hop 160, centred with reflection
 *    padding; the last frame is dropped, leaving [FRAMES] frames;
 *  - power spectrum through [MEL_BINS] Slaney-scale, Slaney-normalised
 *    mel filters over 0–8 kHz;
 *  - `log10(max(x, 1e-10))`, floored at the overall maximum − 8, then
 *    `(x + 4) / 4`.
 *
 * Output is row-major `[MEL_BINS][FRAMES]`, the layout of the model's
 * `input_features` (1 × 128 × 3000).
 */
object WhisperFeatures {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val MEL_BINS = 128
    const val SAMPLES = 30 * SAMPLE_RATE
    const val FRAMES = SAMPLES / HOP

    private const val FREQ_BINS = N_FFT / 2 + 1

    /** `[MEL_BINS][FREQ_BINS]` filter weights. */
    val melFilters: Array<FloatArray> by lazy { buildMelFilters() }

    private val window: FloatArray by lazy {
        FloatArray(N_FFT) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)).toFloat() }
    }

    // DFT tables for the FREQ_BINS non-negative frequencies; 400 is not a
    // power of two, and silent (padding) frames are skipped, so a direct
    // transform costs little next to the encoder.
    private val cosTable: FloatArray by lazy { trigTable { cos(it) } }
    private val sinTable: FloatArray by lazy { trigTable { sin(it) } }

    private fun trigTable(f: (Double) -> Double): FloatArray =
        FloatArray(N_FFT) { i -> f(2.0 * PI * i / N_FFT).toFloat() }

    /** Log-mel features of [samples] (at most 30 s are used). */
    fun compute(samples: FloatArray): FloatArray {
        val audio = FloatArray(SAMPLES)
        System.arraycopy(samples, 0, audio, 0, min(samples.size, SAMPLES))
        val padded = reflectPad(audio, N_FFT / 2)

        val mel = FloatArray(MEL_BINS * FRAMES)
        val frame = FloatArray(N_FFT)
        val power = FloatArray(FREQ_BINS)
        val filters = melFilters
        for (t in 0 until FRAMES) {
            val start = t * HOP
            var silent = true
            for (n in 0 until N_FFT) {
                val v = padded[start + n]
                if (v != 0f) silent = false
                frame[n] = v * window[n]
            }
            if (silent) {
                power.fill(0f)
            } else {
                powerSpectrum(frame, power)
            }
            for (m in 0 until MEL_BINS) {
                val weights = filters[m]
                var sum = 0f
                for (k in 0 until FREQ_BINS) sum += weights[k] * power[k]
                mel[m * FRAMES + t] = log10(max(sum, 1e-10f))
            }
        }
        var top = Float.NEGATIVE_INFINITY
        for (v in mel) if (v > top) top = v
        val floor = top - 8f
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + 4f) / 4f
        return mel
    }

    private fun powerSpectrum(frame: FloatArray, out: FloatArray) {
        for (k in 0 until FREQ_BINS) {
            var re = 0f
            var im = 0f
            var idx = 0
            for (n in 0 until N_FFT) {
                re += frame[n] * cosTable[idx]
                im -= frame[n] * sinTable[idx]
                idx += k
                if (idx >= N_FFT) idx -= N_FFT
            }
            out[k] = re * re + im * im
        }
    }

    /** numpy's `reflect` padding: the edge sample is not repeated. */
    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        val out = FloatArray(x.size + 2 * pad)
        System.arraycopy(x, 0, out, pad, x.size)
        for (i in 0 until pad) {
            out[pad - 1 - i] = x[i + 1]
            out[pad + x.size + i] = x[x.size - 2 - i]
        }
        return out
    }

    private fun hzToMel(hz: Double): Double =
        if (hz < MIN_LOG_HZ) hz / F_SP else MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOG_STEP

    private fun melToHz(mel: Double): Double =
        if (mel < MIN_LOG_MEL) mel * F_SP else MIN_LOG_HZ * exp(LOG_STEP * (mel - MIN_LOG_MEL))

    /** librosa's `filters.mel(sr=16000, n_fft=400, n_mels=128)` (Slaney scale and norm). */
    private fun buildMelFilters(): Array<FloatArray> {
        val fftFreqs = DoubleArray(FREQ_BINS) { it * (SAMPLE_RATE / 2.0) / (FREQ_BINS - 1) }
        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val melF = DoubleArray(MEL_BINS + 2) { melToHz(minMel + (maxMel - minMel) * it / (MEL_BINS + 1)) }
        return Array(MEL_BINS) { m ->
            val lowDiff = melF[m + 1] - melF[m]
            val highDiff = melF[m + 2] - melF[m + 1]
            val enorm = 2.0 / (melF[m + 2] - melF[m])
            FloatArray(FREQ_BINS) { k ->
                val lower = (fftFreqs[k] - melF[m]) / lowDiff
                val upper = (melF[m + 2] - fftFreqs[k]) / highDiff
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }

    private const val F_SP = 200.0 / 3.0
    private const val MIN_LOG_HZ = 1000.0
    private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
    private val LOG_STEP = ln(6.4) / 27.0
}

/** IEEE 754 half-precision conversions for fp16 model tensors (issue #159). */
object Half {

    /** Round-to-nearest-even float → half bits. */
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xff
        var mantissa = bits and 0x7fffff
        if (exp == 0xff) {
            // Inf or NaN (keep NaN quiet).
            return (sign or 0x7c00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        }
        val e = exp - 127 + 15
        if (e >= 0x1f) return (sign or 0x7c00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mantissa = mantissa or 0x800000
            val shift = 14 - e
            var half = mantissa ushr shift
            val rem = mantissa and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        var half = (e shl 10) or (mantissa ushr 13)
        val rem = mantissa and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++
        return (sign or half).toShort()
    }

    fun toFloat(half: Short): Float {
        val h = half.toInt() and 0xffff
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1f
        val mantissa = h and 0x3ff
        val bits = when (exp) {
            0 -> {
                if (mantissa == 0) {
                    sign
                } else {
                    // Subnormal: normalise.
                    var m = mantissa
                    var e = -1
                    while (m and 0x400 == 0) {
                        m = m shl 1
                        e--
                    }
                    // m · 2^-24 with its top bit moved to bit 10.
                    sign or ((e + 114) shl 23) or ((m and 0x3ff) shl 13)
                }
            }
            0x1f -> sign or 0x7f800000 or (mantissa shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
