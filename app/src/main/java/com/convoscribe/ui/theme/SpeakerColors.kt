package com.convoscribe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colours that tell speakers apart, keyed on the 0-based speaker
 * index (never the label, so renaming a speaker keeps its colour on
 * both its chip and its lines). Indices past the end wrap around.
 * Each palette meets 4.5:1 against its scheme's surface, because the
 * colour is also used for the speaker's name text.
 */
@Immutable
data class SpeakerPalette(val colors: List<Color>) {
    operator fun get(speakerIndex: Int): Color = colors[Math.floorMod(speakerIndex, colors.size)]
}

internal val LightSpeakerPalette = SpeakerPalette(
    listOf(
        // teal, amber, indigo, crimson, green, purple
        Color(0xFF00696B),
        Color(0xFF8A5100),
        Color(0xFF3F51B5),
        Color(0xFFB3261E),
        Color(0xFF2E6B30),
        Color(0xFF7B3FA0)
    )
)

internal val DarkSpeakerPalette = SpeakerPalette(
    listOf(
        Color(0xFF80D5D4),
        Color(0xFFFFB95C),
        Color(0xFFB3C5FF),
        Color(0xFFFFB4A9),
        Color(0xFF96D68D),
        Color(0xFFE2B6FF)
    )
)

/** E-ink: near-black first, then deep tones a colour e-ink panel still separates. */
internal val EinkSpeakerPalette = SpeakerPalette(
    listOf(
        Color(0xFF1F1F1F),
        Color(0xFF8B0000),
        Color(0xFF00308B),
        Color(0xFF005A00),
        Color(0xFF6A3D00),
        Color(0xFF5A005A)
    )
)
