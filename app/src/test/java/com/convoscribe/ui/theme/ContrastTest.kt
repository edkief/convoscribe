package com.convoscribe.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast of the colour pairs the screens draw text with, in
 * the light, dark and e-ink schemes: at least 4.5:1 for text, 3:1 for
 * outlines and other non-text UI.
 */
class ContrastTest {

    private val schemes = mapOf(
        "light" to (LightColors to LightSpeakerPalette),
        "dark" to (DarkColors to DarkSpeakerPalette),
        "eink" to (EinkColors to EinkSpeakerPalette)
    )

    @Test
    fun text_pairs_meet_aa() {
        schemes.forEach { (name, pair) ->
            textPairs(pair.first).forEach { (label, colors) ->
                assertContrast("$name $label", colors.first, colors.second, AA_TEXT)
            }
        }
    }

    @Test
    fun outlines_meet_non_text_minimum() {
        schemes.forEach { (name, pair) ->
            val scheme = pair.first
            assertContrast("$name outline/surface", scheme.outline, scheme.surface, AA_NON_TEXT)
        }
    }

    @Test
    fun speaker_colours_are_readable_as_text() {
        schemes.forEach { (name, pair) ->
            val (scheme, palette) = pair
            palette.colors.forEachIndexed { index, color ->
                assertContrast("$name speaker $index/surface", color, scheme.surface, AA_TEXT)
                assertContrast("$name speaker $index/surfaceContainerLow", color, scheme.surfaceContainerLow, AA_TEXT)
                // The playing transcript line is highlighted with this tone.
                assertContrast(
                    "$name speaker $index/surfaceContainerHighest",
                    color,
                    scheme.surfaceContainerHighest,
                    AA_TEXT
                )
            }
        }
    }

    private fun textPairs(s: ColorScheme): Map<String, Pair<Color, Color>> = mapOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onBackground/background" to (s.onBackground to s.background),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "onSurface/surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
        "primary/surfaceContainerHighest" to (s.primary to s.surfaceContainerHighest),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "onSurfaceVariant/surfaceContainerLow" to (s.onSurfaceVariant to s.surfaceContainerLow),
        "primary/surface" to (s.primary to s.surface),
        "primary/surfaceContainerLow" to (s.primary to s.surfaceContainerLow),
        "error/surface" to (s.error to s.surface),
        "onError/error" to (s.onError to s.error),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface)
    )

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        assertTrue("$label is %.2f:1, needs %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private companion object {
        const val AA_TEXT = 4.5
        const val AA_NON_TEXT = 3.0
    }
}
