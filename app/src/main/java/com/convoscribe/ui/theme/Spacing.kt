package com.convoscribe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale. Screens use these instead of literal `dp` values so
 * gaps stay consistent: [xs]/[s] inside a component, [m] between related
 * items, [l] for screen and card padding, [xl]/[xxl] between sections.
 */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    /** Horizontal padding of a screen's content. */
    val screen: Dp = 16.dp
)

/**
 * Fixed sizes of components, so screens do not hard-code them.
 * [touchTarget] is the minimum interactive size: 48 dp normally, 56 dp
 * on e-ink, where the screen is read at arm's length with a stylus or
 * finger and taps are harder to see land.
 */
@Immutable
data class Sizes(
    val touchTarget: Dp = 48.dp,
    val iconSmall: Dp = 18.dp,
    val icon: Dp = 24.dp,
    val iconLarge: Dp = 40.dp,
    val emptyStateIcon: Dp = 64.dp,
    val recordButton: Dp = 88.dp,
    val speakerDot: Dp = 10.dp,
    val levelMeterHeight: Dp = 10.dp,
    val progressStroke: Dp = 2.dp,
    val progressSmall: Dp = 20.dp,
    val outline: Dp = 1.dp,
    val activeOutline: Dp = 2.dp,
    val bubbleMaxWidth: Dp = 320.dp,
    val contentMaxWidth: Dp = 640.dp,
    val elevation: Dp = 1.dp
)

internal val StandardSizes = Sizes()

internal val EinkSizes = Sizes(
    touchTarget = 56.dp,
    icon = 26.dp,
    recordButton = 96.dp,
    speakerDot = 12.dp,
    levelMeterHeight = 14.dp,
    outline = 1.dp,
    elevation = 0.dp
)
