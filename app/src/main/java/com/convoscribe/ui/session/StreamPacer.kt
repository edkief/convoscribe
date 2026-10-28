package com.convoscribe.ui.session

/**
 * Decides when a streamed answer's text is shown again (issue #49).
 * An e-ink panel redrawn for every token flickers and lags, so there
 * the growing answer is shown at most once per [intervalMs]; the first
 * token is always shown at once, and the whole answer replaces the
 * streamed text when it is saved. `0` shows every token, as on an LCD.
 * One pacer per answer.
 */
class StreamPacer(
    private val intervalMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var last: Long? = null

    /** `true` when the text received so far should be shown now. */
    fun due(): Boolean {
        if (intervalMs <= 0L) return true
        val now = clock()
        val previous = last
        if (previous != null && now - previous < intervalMs) return false
        last = now
        return true
    }

    companion object {
        /** How often a streamed answer is redrawn on e-ink. */
        const val EINK_INTERVAL_MS: Long = 1_000L
    }
}
