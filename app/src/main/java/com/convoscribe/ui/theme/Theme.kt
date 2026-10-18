package com.convoscribe.ui.theme

import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalContext

internal val LocalSpacing = staticCompositionLocalOf { Spacing() }
internal val LocalSizes = staticCompositionLocalOf { StandardSizes }
internal val LocalExtendedTypography = staticCompositionLocalOf { StandardExtendedTypography }
internal val LocalSpeakerPalette = staticCompositionLocalOf { LightSpeakerPalette }
internal val LocalEink = staticCompositionLocalOf { false }

/**
 * App theme. The default look is the brand scheme ([LightColors] /
 * [DarkColors]); [dynamicColor] opts into the wallpaper-based scheme on
 * Android 12+. [eink] switches to the e-ink variant: [EinkColors]
 * (light only), tighter [EinkShapes], larger touch targets and text,
 * no ripples and, through [AppTheme.reduceMotion], no animations.
 *
 * Screens read the extra tokens through [AppTheme].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoScribeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    eink: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        eink -> EinkColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val sizes = if (eink) EinkSizes else StandardSizes
    val speakerPalette = when {
        eink -> EinkSpeakerPalette
        darkTheme -> DarkSpeakerPalette
        else -> LightSpeakerPalette
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = if (eink) EinkShapes else StandardShapes
    ) {
        CompositionLocalProvider(
            LocalSpacing provides Spacing(),
            LocalSizes provides sizes,
            LocalExtendedTypography provides if (eink) EinkExtendedTypography else StandardExtendedTypography,
            LocalSpeakerPalette provides speakerPalette,
            LocalEink provides eink,
            LocalMinimumInteractiveComponentSize provides sizes.touchTarget
        ) {
            if (eink) {
                // A ripple is a short animation an e-ink panel shows as
                // smeared grey; turn it off for Material and plain
                // clickables alike.
                CompositionLocalProvider(
                    LocalRippleConfiguration provides null,
                    LocalIndication provides NoIndication,
                    content = content
                )
            } else {
                content()
            }
        }
    }
}

/** Accessors for the tokens [MaterialTheme] has no slot for. */
object AppTheme {
    val spacing: Spacing
        @Composable @ReadOnlyComposable
        get() = LocalSpacing.current

    val sizes: Sizes
        @Composable @ReadOnlyComposable
        get() = LocalSizes.current

    val typography: ExtendedTypography
        @Composable @ReadOnlyComposable
        get() = LocalExtendedTypography.current

    val speakerColors: SpeakerPalette
        @Composable @ReadOnlyComposable
        get() = LocalSpeakerPalette.current

    /** `true` in the e-ink variant: flat outlined surfaces, no shadows. */
    val isEink: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalEink.current

    /** `true` when screens should jump instead of animating (e-ink). */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalEink.current
}

/** Indication that draws nothing, for e-ink. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}
