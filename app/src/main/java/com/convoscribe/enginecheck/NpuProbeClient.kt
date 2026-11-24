package com.convoscribe.enginecheck

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import com.convoscribe.TAG
import com.convoscribe.service.NpuProbeService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Message codes and Bundle keys shared by [NpuProbeClient] and [NpuProbeService]. */
object NpuProbeProtocol {
    const val MSG_RUN = 1
    const val MSG_STARTED = 2
    const val MSG_RESULT = 3

    const val KEY_SAMPLE_FILE = "sampleFile"

    /** What the probe runs: [KIND_NEMOTRON] (the default) or [KIND_WHISPER]. */
    const val KEY_KIND = "kind"
    const val KIND_NEMOTRON = "nemotron"

    /** Whisper on the NPU through onnxruntime's QNN provider (issue #159). */
    const val KIND_WHISPER = "whisper"

    /** Name suffix of the probe process, as declared in the manifest. */
    const val PROCESS_SUFFIX = ":npuprobe"
}

/**
 * Runs the NPU engine check in the isolated `:npuprobe` process
 * and turns a dead probe into a FAILED result with enough
 * diagnostics to troubleshoot it.
 *
 * sherpa-onnx's QNN backend calls `exit()` when HTP device
 * creation fails, so in-process there is nothing to catch: the app
 * just closes. Here the probe dies instead, and we collect:
 *
 *  - the probe's exit reason and status from
 *    [ActivityManager.getHistoricalProcessExitReasons] (API 30+);
 *  - whether the FastRPC / DSP device nodes are visible to the app;
 *  - the probe's own logcat lines (QNN / FastRPC errors, SELinux
 *    denials), which an app can read for its own UID.
 *
 * A dead probe is recorded in [availability] so the recording path
 * refuses the NPU instead of crashing the app; a clean run clears it,
 * marks the NPU verified and calls [onVerified] (which lets the
 * default engine move to the NPU for users who never picked one).
 */
class NpuProbeClient(
    private val context: Context,
    private val availability: NpuAvailability,
    private val onVerified: () -> Unit = {}
) : WhisperNpuProbe {

    private sealed interface Outcome {
        data class Completed(val result: EngineCheckResult) : Outcome
        data class Died(val pid: Int) : Outcome
    }

    /**
     * Run the NPU check on [sampleFile] in the probe process. Never
     * returns a result for a different engine; throws only when the
     * probe service cannot be bound at all.
     */
    suspend fun run(sampleFile: File): EngineCheckResult = runKind(
        sampleFile = sampleFile,
        kind = NpuProbeProtocol.KIND_NEMOTRON,
        engine = "npu",
        onClean = {
            availability.markVerified()
            onVerified()
        },
        onFailed = availability::markFailed
    )

    /**
     * Run Whisper on the NPU (issue #159) over [sampleFile] in the probe
     * process. A clean run marks Whisper verified on the NPU; an error or
     * a dead probe marks it failed, so [com.convoscribe.asr.WhisperEngine]
     * uses the CPU. Nemotron's state is left alone.
     */
    override suspend fun runWhisper(sampleFile: File): EngineCheckResult = runKind(
        sampleFile = sampleFile,
        kind = NpuProbeProtocol.KIND_WHISPER,
        engine = WHISPER_NPU_ENGINE,
        onClean = availability::markWhisperVerified,
        onFailed = availability::markWhisperFailed,
        failedOnError = true
    )

    /**
     * [onClean] after a run without error; [onFailed] with the reason when
     * the probe died, or (with [failedOnError]) when the run returned an
     * error. Nemotron keeps its old rule: an error result (a missing
     * model, say) is not a verdict on the NPU.
     */
    private suspend fun runKind(
        sampleFile: File,
        kind: String,
        engine: String,
        onClean: () -> Unit,
        onFailed: (String) -> Unit,
        failedOnError: Boolean = false
    ): EngineCheckResult {
        return when (val outcome = withContext(Dispatchers.Main) { runInProbe(sampleFile, kind) }) {
            is Outcome.Completed -> {
                val error = outcome.result.error
                if (error == null) {
                    onClean()
                } else if (failedOnError) {
                    onFailed(error)
                }
                outcome.result
            }
            is Outcome.Died -> {
                val exit = withContext(Dispatchers.IO) { findExitInfo(outcome.pid) }
                val diagnostics = withContext(Dispatchers.IO) {
                    formatProbeDiagnostics(
                        pid = outcome.pid,
                        exit = exit,
                        dspNodes = dspNodeStatus(),
                        logLines = readProbeLog(outcome.pid)
                    )
                }
                Log.w(TAG, "npu probe died:\n$diagnostics")
                val message = probeFailureMessage(exit)
                onFailed(message)
                EngineCheckResult(
                    engine = engine,
                    initMs = 0L,
                    firstRun = false,
                    audioMs = 0L,
                    processMs = 0L,
                    text = "",
                    error = message,
                    diagnostics = diagnostics
                )
            }
        }
    }

    /** Bind the probe, ask it to run, and wait for a result or its death. Main thread only. */
    private suspend fun runInProbe(sampleFile: File, kind: String): Outcome = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var probePid = 0
        var finished = false
        lateinit var connection: ServiceConnection

        fun finish(outcome: Outcome) {
            if (finished) return
            finished = true
            runCatching { context.unbindService(connection) }
            cont.resume(outcome)
        }

        val replies = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                when (msg.what) {
                    NpuProbeProtocol.MSG_STARTED -> probePid = msg.arg1
                    NpuProbeProtocol.MSG_RESULT -> finish(Outcome.Completed(msg.data.toEngineCheckResult()))
                }
                true
            }
        )

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    // binderDied runs on a binder thread; hop to main so
                    // `finished` is only ever touched from one thread.
                    service.linkToDeath({ mainHandler.post { finish(Outcome.Died(probePid)) } }, 0)
                    val request = Message.obtain(null, NpuProbeProtocol.MSG_RUN).apply {
                        replyTo = replies
                        data = Bundle().apply {
                            putString(NpuProbeProtocol.KEY_SAMPLE_FILE, sampleFile.absolutePath)
                            putString(NpuProbeProtocol.KEY_KIND, kind)
                        }
                    }
                    Messenger(service).send(request)
                } catch (e: RemoteException) {
                    finish(Outcome.Died(probePid))
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = finish(Outcome.Died(probePid))

            override fun onBindingDied(name: ComponentName) = finish(Outcome.Died(probePid))
        }

        val bound = context.bindService(
            Intent(context, NpuProbeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        if (!bound) {
            finished = true
            runCatching { context.unbindService(connection) }
            cont.resumeWithException(IllegalStateException("Could not start the NPU probe process"))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            mainHandler.post {
                if (!finished) {
                    finished = true
                    runCatching { context.unbindService(connection) }
                }
            }
        }
    }

    /**
     * The system's record of how the probe process ended. The
     * activity manager writes it asynchronously after the binder
     * death we reacted to, so poll briefly before giving up.
     */
    private suspend fun findExitInfo(pid: Int): ProbeExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        repeat(10) {
            val match = runCatching {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            }.getOrDefault(emptyList()).firstOrNull { info ->
                info.processName.endsWith(NpuProbeProtocol.PROCESS_SUFFIX) && (pid == 0 || info.pid == pid)
            }
            if (match != null) {
                return ProbeExit(
                    reason = exitReasonName(match.reason),
                    status = match.status,
                    description = match.description
                )
            }
            delay(200)
        }
        return null
    }

    /**
     * Logcat lines written by the probe process. Apps can read
     * their own UID's log entries without READ_LOGS; `--pid` keeps
     * it to the probe. Capped at the last [MAX_LOG_LINES] lines.
     */
    private fun readProbeLog(pid: Int): List<String> {
        if (pid == 0) return emptyList()
        return runCatching {
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor(5, TimeUnit.SECONDS)
            lines.takeLast(MAX_LOG_LINES)
        }.getOrElse { listOf("(could not read logcat: ${it.message})") }
    }

    /**
     * Whether the FastRPC device nodes (the userspace door to the
     * Hexagon DSPs) and the vendor DSP directory are visible to an
     * untrusted app (SELinux denials show up as "not visible"),
     * plus the remote processors the kernel brought up. A device
     * without a `cdsp` remoteproc has no NPU at all, whatever the
     * app does.
     */
    private fun dspNodeStatus(): List<Pair<String, String>> {
        val nodes = DSP_PATHS.map { path ->
            val f = File(path)
            val status = when {
                !f.exists() -> "not visible"
                f.canRead() -> "readable"
                else -> "present, not readable"
            }
            path to status
        }
        val remoteprocs = runCatching {
            File("/sys/class/remoteproc").listFiles()
                ?.mapNotNull { dir -> runCatching { File(dir, "name").readText().trim() }.getOrNull() }
                ?.sorted()
        }.getOrNull()
        val remoteprocStatus = if (remoteprocs.isNullOrEmpty()) "not readable" else remoteprocs.joinToString(",")
        return nodes + ("remoteprocs" to remoteprocStatus)
    }

    private companion object {
        const val MAX_LOG_LINES = 150

        val DSP_PATHS = listOf(
            "/dev/fastrpc-cdsp",
            "/dev/fastrpc-cdsp-secure",
            "/dev/fastrpc-adsp",
            "/dev/adsprpc-smd",
            "/vendor/dsp"
        )

        @RequiresApi(Build.VERSION_CODES.R)
        fun exitReasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "reason=$reason"
        }
    }
}

/** Runs Whisper on the NPU in the probe process (issue #159); see [NpuProbeClient.runWhisper]. */
fun interface WhisperNpuProbe {
    suspend fun runWhisper(sampleFile: File): EngineCheckResult
}

/** How the probe process ended, from [ApplicationExitInfo]. */
data class ProbeExit(val reason: String, val status: Int, val description: String?)

/**
 * One-line error for the result card and the factory guard. A
 * self-exit with status 255 is sherpa-onnx's `exit(-1)` after a
 * QNN init failure, which is the case worth naming.
 */
fun probeFailureMessage(exit: ProbeExit?): String = when {
    exit == null -> "The NPU runtime closed its process while starting"
    exit.reason == "EXIT_SELF" ->
        "The NPU runtime gave up and exited (status ${exit.status}) while starting"
    else -> "The NPU process died while starting (${exit.reason}, status ${exit.status})"
}

/**
 * Plain-text diagnostics block attached to a FAILED NPU result and
 * printed under `diagnostics:` in the copied report.
 */
fun formatProbeDiagnostics(
    pid: Int,
    exit: ProbeExit?,
    dspNodes: List<Pair<String, String>>,
    logLines: List<String>
): String {
    val sb = StringBuilder()
    sb.append("probePid: ").append(if (pid == 0) "unknown" else pid.toString()).append('\n')
    if (exit == null) {
        sb.append("exit: unknown\n")
    } else {
        sb.append("exit: ").append(exit.reason).append(" status=").append(exit.status)
        if (!exit.description.isNullOrBlank()) sb.append(" (").append(exit.description).append(')')
        sb.append('\n')
    }
    for ((path, status) in dspNodes) {
        sb.append("dsp ").append(path).append(": ").append(status).append('\n')
    }
    sb.append("probeLog:")
    if (logLines.isEmpty()) {
        sb.append(" (none)")
    } else {
        for (line in logLines) sb.append('\n').append(line)
    }
    return sb.toString()
}

/** Serialise an [EngineCheckResult] for the probe's reply message. */
fun EngineCheckResult.toBundle(): Bundle = Bundle().apply {
    putString("engine", engine)
    putLong("initMs", initMs)
    putBoolean("firstRun", firstRun)
    putLong("audioMs", audioMs)
    putLong("processMs", processMs)
    putString("text", text)
    putString("error", error)
    putString("diagnostics", diagnostics)
}

/** Inverse of [toBundle]. */
fun Bundle.toEngineCheckResult(): EngineCheckResult = EngineCheckResult(
    engine = getString("engine") ?: "npu",
    initMs = getLong("initMs"),
    firstRun = getBoolean("firstRun"),
    audioMs = getLong("audioMs"),
    processMs = getLong("processMs"),
    text = getString("text") ?: "",
    error = getString("error"),
    diagnostics = getString("diagnostics")
)
