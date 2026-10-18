package com.convoscribe.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A block of decoded audio as the platform decoder hands it out:
 * [samples] are interleaved floats in `[-1.0, 1.0]` ([channels] per
 * frame) at [sampleRate] Hz.
 */
class PcmBlock(val samples: FloatArray, val channels: Int, val sampleRate: Int) {
    init {
        require(channels > 0) { "channels must be > 0" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
    }
}

/**
 * Turns decoded [PcmBlock]s of any channel count and rate into the
 * app's 16 kHz mono (issue #3): channels are averaged, then
 * [StreamingResampler] brings the rate to [targetRate]. Blocks are
 * converted as they come, so a long file never has to fit in memory;
 * call [flush] after the last one for the resampler's tail.
 *
 * A rate change mid-stream (rare, but a decoder may report a new
 * output format) flushes the old resampler first.
 */
class PcmConverter(private val targetRate: Int = AudioSpec.SAMPLE_RATE) {

    private var resampler: StreamingResampler? = null

    /** Mono samples at [targetRate] for [block]; may be empty while the resampler fills up. */
    fun convert(block: PcmBlock): FloatArray {
        val mono = downmix(block.samples, block.channels)
        val current = resampler
        if (current != null && current.fromRate == block.sampleRate) return current.process(mono)
        val tail = current?.flush() ?: FloatArray(0)
        val next = StreamingResampler(block.sampleRate, targetRate)
        resampler = next
        val head = next.process(mono)
        return if (tail.isEmpty()) head else tail + head
    }

    /** The samples still held by the resampler; the converter can then take a new stream. */
    fun flush(): FloatArray {
        val tail = resampler?.flush() ?: FloatArray(0)
        resampler = null
        return tail
    }

    companion object {
        /** Average each frame of interleaved [samples] to one value. */
        fun downmix(samples: FloatArray, channels: Int): FloatArray {
            if (channels == 1) return samples
            val frames = samples.size / channels
            return FloatArray(frames) { f ->
                var sum = 0f
                val start = f * channels
                for (c in 0 until channels) sum += samples[start + c]
                sum / channels
            }
        }
    }
}

/**
 * Band-limited sample-rate conversion from [fromRate] to [toRate],
 * fed in blocks of any size. Each output sample is a windowed-sinc
 * (Hann) interpolation of the input around its position; the sinc's
 * cutoff sits just below the lower of the two Nyquist frequencies, so
 * downsampling 44.1 or 48 kHz audio does not fold its top octave
 * into the speech band the way linear interpolation would.
 *
 * The ratio is reduced to `toRate/fromRate = phases/step`, and the
 * filter weights for each of the `phases` fractional positions are
 * computed once. The output of a whole stream is
 * `floor(inputSamples * toRate / fromRate)` samples, whatever the
 * block sizes, and the same as converting it in one block.
 *
 * @param zeroCrossings sinc lobes kept on each side; more is sharper
 *   and slower.
 */
class StreamingResampler(val fromRate: Int, val toRate: Int, zeroCrossings: Int = 10) {

    private val phases: Int
    private val step: Int
    private val half: Int
    private val weights: Array<FloatArray>

    init {
        require(fromRate > 0 && toRate > 0) { "rates must be > 0" }
        val g = gcd(fromRate, toRate)
        phases = toRate / g
        step = fromRate / g
        val cutoff = CUTOFF * min(1.0, toRate.toDouble() / fromRate)
        half = ceil(zeroCrossings / cutoff).toInt()
        weights = if (fromRate == toRate) {
            emptyArray()
        } else {
            Array(phases) { p ->
                val frac = p.toDouble() / phases
                val taps = DoubleArray(2 * half) { j -> kernel(frac - (j - half + 1), cutoff) }
                val sum = taps.sum()
                FloatArray(taps.size) { (taps[it] / sum).toFloat() }
            }
        }
    }

    /** Input kept for the next outputs; `buffer[0]` is input sample [bufferStart]. */
    private var buffer = FloatArray(0)
    private var bufferLength = 0
    private var bufferStart = 0L
    private var inputCount = 0L
    private var outputIndex = 0L

    /** Output samples [samples] completes; the last few wait for more input or [flush]. */
    fun process(samples: FloatArray): FloatArray {
        if (fromRate == toRate) {
            inputCount += samples.size
            return samples.copyOf()
        }
        if (inputCount == 0L && bufferLength == 0) {
            // History before the first sample is silence.
            bufferStart = -(half - 1).toLong()
            append(FloatArray(half - 1))
        }
        append(samples)
        inputCount += samples.size
        return drain(limit = Long.MAX_VALUE)
    }

    /** End of input: the outputs still owed, with silence after the last sample. */
    fun flush(): FloatArray {
        if (fromRate == toRate) return FloatArray(0)
        val total = inputCount * phases / step
        if (outputIndex >= total) return FloatArray(0)
        append(FloatArray(half + 1))
        return drain(limit = total)
    }

    private fun drain(limit: Long): FloatArray {
        val available = bufferStart + bufferLength - 1
        // Upper bound on the outputs this buffer completes (+1 for rounding).
        val reachable = (available - half + 1).coerceAtLeast(0L) * phases / step + 1
        val capacity = (minOf(limit, reachable) - outputIndex).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val out = FloatArray(capacity)
        var count = 0
        while (outputIndex < limit && count < capacity) {
            val scaled = outputIndex * step
            val center = scaled / phases
            if (center + half > available) break
            val taps = weights[(scaled % phases).toInt()]
            val first = (center - half + 1 - bufferStart).toInt()
            var acc = 0f
            for (j in taps.indices) acc += taps[j] * buffer[first + j]
            out[count++] = acc
            outputIndex++
        }
        // Drop what no later output can reach.
        val keepFrom = outputIndex * step / phases - half + 1
        val drop = (keepFrom - bufferStart).toInt().coerceIn(0, bufferLength)
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, bufferLength - drop)
            bufferLength -= drop
            bufferStart += drop
        }
        return if (count == out.size) out else out.copyOf(count)
    }

    private fun append(samples: FloatArray) {
        if (bufferLength + samples.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, bufferLength + samples.size))
        }
        System.arraycopy(samples, 0, buffer, bufferLength, samples.size)
        bufferLength += samples.size
    }

    /** Hann-windowed sinc at distance [x] input samples, low-passed at [cutoff] of the input Nyquist. */
    private fun kernel(x: Double, cutoff: Double): Double {
        if (x <= -half || x >= half) return 0.0
        val window = 0.5 * (1 + cos(PI * x / half))
        val arg = PI * cutoff * x
        val sinc = if (x == 0.0) 1.0 else sin(arg) / arg
        return cutoff * sinc * window
    }

    private companion object {
        /** Fraction of the lower Nyquist the filter passes; the rest is the transition band. */
        const val CUTOFF = 0.95

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
