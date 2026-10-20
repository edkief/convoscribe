package com.convoscribe.enginecheck

import android.content.SharedPreferences
import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.StreamingCleaner
import com.convoscribe.audio.aligned

/**
 * One timing of the noise cleaner (issue #37) over the engine
 * check's sample clip: [processMs] to clean [audioMs] of audio, or
 * [error] when it could not run.
 */
data class CleanupCheckResult(
    val audioMs: Long,
    val processMs: Long,
    val error: String? = null
) {
    /** `processMs / audioMs`, like [EngineCheckResult.realTimeFactor]; `0.0` without audio. */
    val realTimeFactor: Double get() = if (audioMs == 0L) 0.0 else processMs.toDouble() / audioMs
}

/**
 * Whether cleaning live is a good idea on this device, from the
 * combined real-time factor of the recognizer and the cleaner. Live
 * cleaning runs both on one thread, chunk after chunk, so their
 * factors add up. The thresholds are the engine check's own:
 * under 0.5 keeps up with room to spare, under 1.0 only just.
 */
enum class LiveCleanupVerdict {
    RECOMMENDED,
    MARGINAL,
    NOT_RECOMMENDED;

    companion object {
        fun of(combinedRtf: Double): LiveCleanupVerdict = when {
            combinedRtf < 0.5 -> RECOMMENDED
            combinedRtf < 1.0 -> MARGINAL
            else -> NOT_RECOMMENDED
        }
    }
}

/**
 * Time [cleaner] over [samples] in recording-sized chunks, as live
 * cleaning feeds it, then close it. An exception becomes
 * [CleanupCheckResult.error] instead of escaping.
 */
fun measureCleanup(cleaner: StreamingCleaner, samples: FloatArray, clock: () -> Long): CleanupCheckResult {
    val audioMs = samples.size.toLong() * 1000L / AudioSpec.SAMPLE_RATE
    return try {
        val aligned = cleaner.aligned()
        val start = clock()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + AudioSpec.CHUNK_SAMPLES, samples.size)
            aligned.process(samples.copyOfRange(offset, end))
            offset = end
        }
        aligned.flush()
        CleanupCheckResult(audioMs = audioMs, processMs = clock() - start)
    } catch (t: Throwable) {
        CleanupCheckResult(audioMs = audioMs, processMs = 0L, error = t.message ?: t.toString())
    } finally {
        runCatching { cleaner.close() }
    }
}

/**
 * The last live-cleanup measurement per ASR engine (`"cpu"` /
 * `"npu"`), kept so the Record screen and Settings can warn when live
 * cleaning is not recommended on this device.
 */
class LiveCleanupStore(private val prefs: SharedPreferences) {

    /** Remember the combined real-time factor measured for [engine]. */
    fun record(engine: String, combinedRtf: Double) {
        prefs.edit().putFloat(key(engine), combinedRtf.toFloat()).apply()
    }

    /** The verdict for [engine], or `null` when it was never checked. */
    fun verdict(engine: String): LiveCleanupVerdict? =
        prefs.getFloat(key(engine), -1f).takeIf { it >= 0f }?.let { LiveCleanupVerdict.of(it.toDouble()) }

    private fun key(engine: String) = "combinedRtf.$engine"

    companion object {
        const val PREFS_NAME = "live_cleanup_check"
    }
}
