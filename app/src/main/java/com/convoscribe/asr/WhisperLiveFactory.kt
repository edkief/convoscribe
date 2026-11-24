package com.convoscribe.asr

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.audio.AudioSpec

/**
 * The recorder's [TranscriberFactory] (issue #160): live Whisper when
 * [enabled] (the experimental setting) and Whisper is downloaded,
 * otherwise the [streaming] engine. Whisper is loaded in [create], so in
 * the Record screen's LOADING phase, and kept for the recording;
 * segments are cut at pauses by a [voiceDetector] capped at
 * [maxSeconds].
 */
class WhisperLiveFactory(
    private val streaming: TranscriberFactory,
    private val whisper: WhisperEngine,
    private val enabled: () -> Boolean,
    private val maxSeconds: () -> Int,
    private val voiceDetector: (maxSeconds: Int) -> VoiceDetector
) : TranscriberFactory {

    /** What the last live [create] decodes with, for [describe]. */
    @Volatile
    private var lastInfo: EngineInfo? = null

    private fun live(): Boolean {
        if (!enabled()) return false
        return whisper.isAvailable().also {
            if (!it) Log.w(TAG, "live Whisper is on but not downloaded; using the streaming engine")
        }
    }

    override fun create(language: String): StreamingTranscriber {
        if (!live()) return streaming.create(language)
        val seconds = maxSeconds()
        val decoder = whisper.openDecoder(language)
        lastInfo = decoder.info
        return try {
            WhisperLiveTranscriber(decoder, { voiceDetector(seconds) }, seconds * AudioSpec.SAMPLE_RATE)
        } catch (t: Throwable) {
            runCatching { decoder.close() }
            throw t
        }
    }

    override fun needsPreparation(language: String): Boolean =
        if (enabled() && whisper.isAvailable()) false else streaming.needsPreparation(language)

    override fun describe(language: String): EngineInfo =
        if (enabled() && whisper.isAvailable()) lastInfo ?: whisper.describe() else streaming.describe(language)
}
