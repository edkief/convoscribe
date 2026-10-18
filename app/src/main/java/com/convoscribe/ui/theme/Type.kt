package com.convoscribe.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Base = Typography()

/**
 * Material 3's scale on the system sans, with firmer weights on titles
 * and labels so headers read as headers on a low-contrast e-ink panel.
 */
internal val AppTypography = Typography(
    displayLarge = Base.displayLarge,
    displayMedium = Base.displayMedium,
    displaySmall = Base.displaySmall,
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold)
)

/** Text styles the Material scale has no slot for. */
@Immutable
data class ExtendedTypography(
    /** Transcript and live-transcript lines: a little larger, with generous leading. */
    val transcript: TextStyle,
    /** Speaker / timestamp line above a transcript line. */
    val transcriptMeta: TextStyle,
    /** Recording timer: large, with tabular figures so it does not jitter. */
    val timer: TextStyle,
    /** Small figures that change in place (player times, durations). */
    val figures: TextStyle
)

private const val TABULAR = "tnum"

internal val StandardExtendedTypography = ExtendedTypography(
    transcript = Base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
    transcriptMeta = AppTypography.labelMedium.copy(letterSpacing = 0.02.em),
    timer = Base.displayMedium.copy(fontWeight = FontWeight.Light, fontFeatureSettings = TABULAR),
    figures = AppTypography.labelMedium.copy(fontFeatureSettings = TABULAR)
)

/** E-ink: larger transcript text and a heavier timer, which ghost less on refresh. */
internal val EinkExtendedTypography = StandardExtendedTypography.copy(
    transcript = Base.bodyLarge.copy(fontSize = 19.sp, lineHeight = 29.sp),
    transcriptMeta = AppTypography.labelLarge,
    timer = Base.displayMedium.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR)
)
