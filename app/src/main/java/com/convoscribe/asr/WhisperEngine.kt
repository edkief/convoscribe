package com.convoscribe.asr

import android.util.Log
import com.convoscribe.TAG
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * What is on record about Whisper on the NPU (issue #159), written by the
 * NPU probe and by [WhisperEngine] itself
 * ([com.convoscribe.enginecheck.NpuAvailability] in the app).
 */
interface WhisperNpuState {
    /** Why Whisper must not use the NPU, or `null`. */
    fun blockedReason(): String?

    /** The probe ran Whisper cleanly on the NPU. */
    fun isVerified(): Boolean

    fun markFailed(reason: String)
}

/** Which recognizer Whisper uses on this device right now, and why (issue #159). */
data class WhisperStatus(
    /** `"npu"`, `"cpu"`, or `null` when Whisper cannot run. */
    val engine: String?,
    /** The chip's marketing name when it has a published NPU build. */
    val npuChip: String?,
    val npuInstalled: Boolean,
    val npuVerified: Boolean,
    val npuBlockedReason: String?,
    val cpuInstalled: Boolean
) {
    /** The chip has an NPU build, so the NPU download is offered. */
    val npuSupported: Boolean get() = npuChip != null

    /** Anything of Whisper is downloaded. */
    val anyInstalled: Boolean get() = npuInstalled || cpuInstalled
}

/**
 * On-device Whisper (issues #158, #159): the NPU build when this chip
 * has one, it is downloaded and the NPU probe ran it cleanly (and no
 * failure is on record since), otherwise the CPU model when it is
 * downloaded.
 *
 * A failure on the NPU is recorded in [npuState], so later calls use the
 * CPU until the NPU is checked again, and the same call is retried on the
 * CPU when that model is downloaded; otherwise it is rethrown and the
 * caller keeps its live transcript. [describe] names the recognizer that
 * produced the last result, so the session records `npu` or `cpu`.
 */
class WhisperEngine(
    private val npu: WhisperRecognizer,
    private val cpu: WhisperRecognizer,
    private val npuChip: () -> String?,
    private val npuInstalled: () -> Boolean,
    private val cpuInstalled: () -> Boolean,
    private val npuState: WhisperNpuState
) : FileRecognizer {

    @Volatile
    private var lastUsed: FileRecognizer? = null

    fun status(): WhisperStatus {
        val chip = npuChip()
        val npuOn = chip != null && npuInstalled()
        val blocked = npuState.blockedReason()
        val verified = npuState.isVerified()
        val cpuOn = cpuInstalled()
        val engine = when {
            npuOn && verified && blocked == null -> "npu"
            cpuOn -> "cpu"
            else -> null
        }
        return WhisperStatus(engine, chip, npuOn, verified, blocked, cpuOn)
    }

    /** Whisper can transcribe now, on the NPU or the CPU. */
    fun isAvailable(): Boolean = status().engine != null

    override suspend fun transcribe(
        file: File,
        language: String,
        onProgress: (Float) -> Unit
    ): List<TranscriptEvent.Final> {
        val status = status()
        if (status.engine == "npu") {
            try {
                return npu.transcribe(file, language, onProgress).also { lastUsed = npu }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                npuFailed(t)
                onProgress(0f)
            }
        } else if (status.engine == null) {
            throw IllegalStateException("Whisper is not downloaded")
        }
        return cpu.transcribe(file, language, onProgress).also { lastUsed = cpu }
    }

    /**
     * Whisper kept loaded for a live recording (issue #160), on the
     * recognizer [transcribe] would use. A failure to open or decode on
     * the NPU is recorded in [npuState] like one in [transcribe], and
     * the decoder carries on with the CPU model (retrying the segment)
     * when it is downloaded; otherwise the failure is thrown.
     */
    fun openDecoder(language: String): WhisperDecoder = when (status().engine) {
        "npu" -> {
            val first = try {
                npu.openDecoder(language)
            } catch (t: Throwable) {
                null.also { npuFailed(t) }
            }
            first?.let { FallbackDecoder(it, language) } ?: cpu.openDecoder(language)
        }
        "cpu" -> cpu.openDecoder(language)
        else -> throw IllegalStateException("Whisper is not downloaded")
    }

    /** Record an NPU failure; throws when there is no CPU model to fall back to. */
    private fun npuFailed(t: Throwable) {
        val reason = t.message ?: t.toString()
        Log.w(TAG, "Whisper failed on the NPU; using the CPU from now on", t)
        npuState.markFailed(reason)
        if (!cpuInstalled()) {
            throw IllegalStateException(
                "Whisper failed on the NPU ($reason). Download the CPU model in Settings to use it instead.",
                t
            )
        }
    }

    /** The NPU decoder, swapped for the CPU one on its first failure. */
    private inner class FallbackDecoder(private var current: WhisperDecoder, private val language: String) :
        WhisperDecoder {
        private var onNpu = true

        override val info: EngineInfo get() = current.info

        override fun decode(samples: FloatArray): String {
            if (!onNpu) return current.decode(samples)
            return try {
                current.decode(samples)
            } catch (t: Throwable) {
                // Throws when there is no CPU model; [close] still
                // releases the NPU decoder then.
                npuFailed(t)
                runCatching { current.close() }
                onNpu = false
                current = cpu.openDecoder(language)
                current.decode(samples)
            }
        }

        override fun close() = current.close()
    }

    override fun describe(): EngineInfo = (lastUsed ?: if (status().engine == "npu") npu else cpu).describe()
}
