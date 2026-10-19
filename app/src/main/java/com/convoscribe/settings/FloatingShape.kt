package com.convoscribe.settings

/**
 * Shape of the floating recording control (issue #21). [key] is the
 * persisted value of [AppSettings.floatingShape].
 *
 *  * [MINIMAL]: a status dot, Pause/Resume and Stop. Nothing in it
 *    changes on its own, so on e-ink the overlay never refreshes the
 *    panel between taps.
 *  * [STANDARD]: the dot, *Recording* or *Paused* and the recorded
 *    time, then Pause/Resume and Stop (the #7 control).
 *  * [EXPANDED]: [STANDARD] plus the last transcribed sentences.
 */
enum class FloatingShape(val key: String) {
    MINIMAL("minimal"),
    STANDARD("standard"),
    EXPANDED("expanded");

    /** The shape the control's expand/collapse button goes to: one step bigger, back to [MINIMAL] from [EXPANDED]. */
    fun next(): FloatingShape = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The shape stored as [key], or `null` for an unknown value or none. */
        fun fromKey(key: String?): FloatingShape? = entries.firstOrNull { it.key == key }

        /**
         * The shape to draw: the one stored as [key], or, while the
         * user has not chosen one, [MINIMAL] on e-ink ([eink]) and
         * [STANDARD] otherwise.
         */
        fun resolve(key: String?, eink: Boolean): FloatingShape = fromKey(key) ?: if (eink) MINIMAL else STANDARD
    }
}
