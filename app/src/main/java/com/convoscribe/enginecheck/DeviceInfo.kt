package com.convoscribe.enginecheck

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/**
 * Snapshot of the bits of device state that determine whether the
 * NPU engine will work and how fast it will be.
 *
 * @property manufacturer `Build.MANUFACTURER`.
 * @property model `Build.MODEL`.
 * @property socManufacturer `Build.SOC_MANUFACTURER` on API 31+
 *   (`"unknown"` on older devices).
 * @property socModel `Build.SOC_MODEL` on API 31+, otherwise
 *   `"unknown"`.
 * @property hardware `Build.HARDWARE`.
 * @property androidVersion `Build.VERSION.RELEASE` (e.g. `"14"`).
 * @property cdspLibraryPresent `true` when
 *   `/vendor/lib64/libcdsprpc.so` or
 *   `/system/vendor/lib64/libcdsprpc.so` is on disk — a hard
 *   prerequisite for the Hexagon NPU.
 * @property htpSkelLibs names of every `libQnnHtpV*Skel.so` found
 *   in the app's `nativeLibraryDir`, sorted lexicographically.
 *   These are the HTP-skel modules sherpa-onnx's QNN backend
 *   loads on the first NPU start; their presence is a quick
 *   smoke-test that the QNN runtime is shipping the right
 *   assets.
 * @property freeStorageBytes bytes available on the partition
 *   that holds the app's `filesDir` (where the NPU context
 *   binaries are cached). The engine check warns below 1.5 GB.
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val androidVersion: String,
    val cdspLibraryPresent: Boolean,
    val htpSkelLibs: List<String>,
    val freeStorageBytes: Long
) {
    companion object {

        /**
         * Build a [DeviceInfo] from the current process. Pure
         * function over the Android SDK and the filesystem; called
         * from the engine-check screen on first composition.
         */
        fun read(context: Context): DeviceInfo {
            val cdspLibraryPresent = File("/vendor/lib64/libcdsprpc.so").exists() ||
                File("/system/vendor/lib64/libcdsprpc.so").exists()
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
            val htpSkelLibs = if (nativeDir.isEmpty()) {
                emptyList()
            } else {
                val nativeDirFile = File(nativeDir)
                if (nativeDirFile.isDirectory) {
                    nativeDirFile.listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.name.startsWith("libQnnHtpV") && it.name.endsWith("Skel.so") }
                        .map { it.name }
                        .sorted()
                } else {
                    emptyList()
                }
            }
            val statFs = StatFs(context.filesDir.path)
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                socManufacturer = socManufacturerOrUnknown(),
                socModel = socModelOrUnknown(),
                hardware = Build.HARDWARE,
                androidVersion = Build.VERSION.RELEASE,
                cdspLibraryPresent = cdspLibraryPresent,
                htpSkelLibs = htpSkelLibs,
                freeStorageBytes = statFs.availableBytes
            )
        }

        private fun socManufacturerOrUnknown(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            "unknown"
        }

        private fun socModelOrUnknown(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            "unknown"
        }
    }
}

/**
 * `Build.SOC_MODEL` (e.g. `SM8650`), or `null` before Android 12 or when
 * the device does not say. Picks the Whisper NPU build (issue #159).
 */
fun currentSocModel(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Build.SOC_MODEL.takeUnless { it.isNullOrBlank() || it == Build.UNKNOWN }
} else {
    null
}

/**
 * Render [info] and [results] as a plain-text report that the
 * engine-check screen's `Copy report` button puts on the
 * clipboard and the share button sends through `ACTION_SEND`.
 *
 * One `key: value` per line so it pastes cleanly into a chat. The
 * section ordering is fixed (device first, then one block per
 * result) so two runs on the same device produce diff-able text.
 *
 * @param info device snapshot taken at the top of the screen.
 * @param results zero, one or two [EngineCheckResult]s (the
 *   user may have run only one of the two engines). The order in
 *   the report matches the order passed in.
 */
fun formatReport(info: DeviceInfo, results: List<EngineCheckResult>): String {
    val sb = StringBuilder()
    sb.append("manufacturer: ").append(info.manufacturer).append('\n')
    sb.append("model: ").append(info.model).append('\n')
    sb.append("socManufacturer: ").append(info.socManufacturer).append('\n')
    sb.append("socModel: ").append(info.socModel).append('\n')
    sb.append("hardware: ").append(info.hardware).append('\n')
    sb.append("androidVersion: ").append(info.androidVersion).append('\n')
    sb.append("cdspLibraryPresent: ").append(info.cdspLibraryPresent).append('\n')
    sb.append("htpSkelLibs: ").append(info.htpSkelLibs.joinToString(",")).append('\n')
    sb.append("freeStorage: ").append("%.1f GB".format(info.freeStorageBytes / 1_000_000_000.0)).append('\n')
    for (r in results) {
        sb.append("engine: ").append(r.engine).append('\n')
        sb.append("firstRun: ").append(if (r.firstRun) "yes" else "no").append('\n')
        sb.append("initMs: ").append(r.initMs).append('\n')
        sb.append("audioMs: ").append(r.audioMs).append('\n')
        sb.append("audioS: ").append("%.2f".format(r.audioMs / 1000.0)).append('\n')
        sb.append("processMs: ").append(r.processMs).append('\n')
        sb.append("processS: ").append("%.2f".format(r.processMs / 1000.0)).append('\n')
        sb.append("RTF: ").append("%.2f".format(r.realTimeFactor)).append('\n')
        sb.append("verdict: ").append(verdict(r)).append('\n')
        if (r.error != null) {
            sb.append("error: ").append(r.error).append('\n')
        } else {
            sb.append("text: ").append(r.text).append('\n')
        }
        if (r.diagnostics != null) {
            sb.append("diagnostics:\n")
            r.diagnostics.lineSequence().forEach { sb.append("  ").append(it).append('\n') }
        }
    }
    return sb.toString()
}

/**
 * Map an [EngineCheckResult] to a one-line verdict for the report
 * and the UI:
 *
 *  - `error != null` ⇒ `"FAILED"`.
 *  - `RTF < 0.5` ⇒ `"OK – fast enough for live transcription"`.
 *  - `RTF < 1.0` ⇒ `"MARGINAL – may lag in long recordings"`.
 *  - else `"TOO SLOW for live transcription"`.
 *
 * Picked by the engine check; the unit test covers the boundary
 * cases (`0.49`, `0.5`, `1.0`).
 */
fun verdict(r: EngineCheckResult): String {
    if (r.error != null) return "FAILED"
    val rtf = r.realTimeFactor
    return when {
        rtf < 0.5 -> "OK – fast enough for live transcription"
        rtf < 1.0 -> "MARGINAL – may lag in long recordings"
        else -> "TOO SLOW for live transcription"
    }
}

/**
 * Storage-space warning shown above the engine-check buttons.
 *
 * Returns the human-readable warning string when:
 *
 *  - the NPU model has not been prepared yet (`npuPrepared == false`), AND
 *  - the available bytes are below 1.5 GB (1_500_000_000 bytes).
 *
 * Returns `null` otherwise — either the NPU is already cached and
 * the first-run compile won't happen, or there is enough free
 * space. The 1.5 GB threshold matches the bundle size plus a
 * safety margin; values above the threshold are tolerated even
 * when `npuPrepared` is false so the user can still benchmark
 * the CPU engine.
 */
fun storageWarning(freeBytes: Long, npuPrepared: Boolean): String? {
    if (npuPrepared) return null
    return if (freeBytes < 1_500_000_000L) {
        "Less than 1.5 GB free: preparing the NPU model may fail. Free up storage first."
    } else {
        null
    }
}
