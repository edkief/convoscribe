package com.convoscribe.ui.theme

import androidx.compose.ui.MotionDurationScale

/**
 * Speed of every Compose animation in the app, installed in the
 * window's recomposer by `MainActivity`. In e-ink mode the scale is 0,
 * so menus, dialogs, tab indicators, switches and progress indicators
 * jump to their end state instead of animating (an animation on an
 * e-ink panel is a smear of partial refreshes). Otherwise it is the
 * system's animator duration scale, as Compose uses by default.
 *
 * Compose reads [scaleFactor] on every animation frame, so changing
 * [eink] takes effect at once.
 */
class AppMotionScale(private val systemScale: () -> Float) : MotionDurationScale {
    @Volatile
    var eink: Boolean = false

    override val scaleFactor: Float
        get() = if (eink) 0f else systemScale()
}
