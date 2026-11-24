package com.convoscribe.enginecheck

import android.content.SharedPreferences
import com.convoscribe.asr.WhisperNpuState

/**
 * Remembers whether the NPU probe has seen the QNN runtime kill
 * its process on this device.
 *
 * sherpa-onnx's QNN backend calls `exit()` when it cannot create
 * the HTP device (seen on the Boox Note Air 6C, whose SoC ships
 * with the NSP fused off and no CDSP), so starting the NPU in the
 * app process closes the whole app with no exception to catch.
 * The NPU probe runs in
 * its own process and records the outcome here; the main
 * [com.convoscribe.engine.sherpa.SherpaTranscriberFactory] reads
 * [blockedReason] and refuses to start the NPU instead of dying.
 *
 * Only the main process writes this store, so the
 * SharedPreferences multi-process caveat does not apply.
 */
class NpuAvailability(private val prefs: SharedPreferences) {

    /**
     * Short reason the NPU must not be started in-process (e.g.
     * `"the NPU runtime exited with status 255 during the last
     * engine check"`), or `null` when no failure is on record.
     */
    fun blockedReason(): String? = prefs.getString(KEY_BLOCKED_REASON, null)

    /**
     * `true` once an NPU probe ran cleanly on this device and no failure
     * has been seen since. The default ASR engine is NPU only then.
     */
    fun isVerified(): Boolean = prefs.getBoolean(KEY_VERIFIED, false)

    /** Record that the probe process died while starting the NPU. */
    fun markFailed(reason: String) {
        prefs.edit().putString(KEY_BLOCKED_REASON, reason).putBoolean(KEY_VERIFIED, false).apply()
    }

    /** Record a clean NPU probe run: clears any failure and marks the NPU verified. */
    fun markVerified() {
        prefs.edit().remove(KEY_BLOCKED_REASON).putBoolean(KEY_VERIFIED, true).apply()
    }

    /**
     * Why Whisper must not use the NPU (issue #159): its probe died or
     * failed, or a transcription on the NPU failed. `null` when none is on
     * record. Kept apart from Nemotron's state: the two use different
     * runtimes (sherpa-onnx's QNN backend and onnxruntime's QNN provider).
     */
    fun whisperBlockedReason(): String? = prefs.getString(KEY_WHISPER_BLOCKED_REASON, null)

    /** `true` once Whisper ran cleanly on the NPU and no failure has been seen since. */
    fun isWhisperVerified(): Boolean = prefs.getBoolean(KEY_WHISPER_VERIFIED, false)

    /** Record that Whisper failed on the NPU; it uses the CPU until checked again. */
    fun markWhisperFailed(reason: String) {
        prefs.edit().putString(KEY_WHISPER_BLOCKED_REASON, reason).putBoolean(KEY_WHISPER_VERIFIED, false).apply()
    }

    /** Record a clean Whisper run on the NPU. */
    fun markWhisperVerified() {
        prefs.edit().remove(KEY_WHISPER_BLOCKED_REASON).putBoolean(KEY_WHISPER_VERIFIED, true).apply()
    }

    /** Forget Whisper's NPU state, e.g. when its model is deleted. */
    fun clearWhisper() {
        prefs.edit().remove(KEY_WHISPER_BLOCKED_REASON).remove(KEY_WHISPER_VERIFIED).apply()
    }

    companion object {
        const val PREFS_NAME = "npu_availability"
        private const val KEY_BLOCKED_REASON = "blockedReason"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_WHISPER_BLOCKED_REASON = "whisperBlockedReason"
        private const val KEY_WHISPER_VERIFIED = "whisperVerified"
    }
}

/** Whisper's side of [NpuAvailability], as [com.convoscribe.asr.WhisperEngine] reads and writes it. */
fun NpuAvailability.whisperState(): WhisperNpuState = object : WhisperNpuState {
    override fun blockedReason(): String? = whisperBlockedReason()
    override fun isVerified(): Boolean = isWhisperVerified()
    override fun markFailed(reason: String) = markWhisperFailed(reason)
}
