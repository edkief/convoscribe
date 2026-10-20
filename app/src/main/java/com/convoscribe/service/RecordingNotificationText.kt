package com.convoscribe.service

import androidx.annotation.StringRes
import com.convoscribe.R
import com.convoscribe.recording.ImportStep
import java.util.Locale
import kotlin.math.max

/**
 * Format the text we display in the foreground-recording
 * notification while a session is in progress.
 *
 *  - `mm:ss` while the elapsed time stays under an hour.
 *  - `h:mm:ss` (single-digit hours, always two-digit minutes/seconds)
 *    once it crosses an hour, so the user can tell at a glance that
 *    a long meeting is still going.
 *
 * The output is rebuilt from the freshest elapsed-counter whenever
 * the foreground service's state flow ticks, but at most once a
 * second (see [RecordingService]) — the format is cheap so the
 * formatter does not carry state of its own.
 *
 * While [paused] (issue #7) the prefix reads "Paused" and the time
 * is frozen at the audio recorded so far.
 *
 * With [marks] (issue #30) the text ends with the count, so a Mark
 * tapped in the notification shows it was taken.
 *
 * Negative inputs are clamped to zero so a transient timer glitch
 * (e.g. `AudioRecord` paused without the controller noticing)
 * cannot throw here.
 */
fun recordingNotificationText(elapsedMs: Long, paused: Boolean = false, marks: Int = 0): String {
    val safe = max(0L, elapsedMs)
    val totalSeconds = safe / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val body = if (hours > 0L) {
        // h:mm:ss — single-digit hours, padded minutes/seconds.
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        // mm:ss — always two digits each.
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
    val text = if (paused) "Paused – $body" else "Recording – $body"
    return when {
        marks <= 0 -> text
        marks == 1 -> "$text · 1 mark"
        else -> "$text · $marks marks"
    }
}

/** String resource for [step]'s progress line, `"Transcribing… 42%"` (takes the percent). */
@StringRes
fun importStepText(step: ImportStep): Int = when (step) {
    ImportStep.COPYING -> R.string.import_step_copying
    ImportStep.DECODING -> R.string.import_step_decoding
    ImportStep.TRANSCRIBING -> R.string.import_step_transcribing
    ImportStep.IDENTIFYING_SPEAKERS -> R.string.import_step_identifying
}
