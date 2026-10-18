package com.convoscribe.settings

/**
 * When speakers are identified after a transcript is done (issue #11).
 * [key] is the persisted value of [AppSettings.speakerIdMode].
 *
 *  * [AUTO]: straight away, as before. Sessions left pending (an earlier
 *    mode, a crash or an interrupted run) are picked up on the next charge.
 *  * [CHARGING]: straight away if the device is charging, otherwise the
 *    session waits until it is plugged in.
 *  * [MANUAL]: never automatically; the user starts it per session.
 */
enum class SpeakerIdMode(val key: String) {
    AUTO("auto"),
    CHARGING("charging"),
    MANUAL("manual");

    /** Whether a transcript just finished should be diarized now, given whether the device [charging]. */
    fun runsNow(charging: Boolean): Boolean = when (this) {
        AUTO -> true
        CHARGING -> charging
        MANUAL -> false
    }

    companion object {
        /** The mode stored as [key], or `null` for an unknown value. */
        fun fromKey(key: String): SpeakerIdMode? = entries.firstOrNull { it.key == key }

        /** The mode stored as [key], or [AUTO] for an unknown value. */
        fun of(key: String): SpeakerIdMode = fromKey(key) ?: AUTO
    }
}
