package com.convoscribe.ui.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.convoscribe.R
import com.convoscribe.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * Microphone level as a row of [SEGMENTS] blocks, lit from the left in
 * proportion to [level] (`0f..1f`, see
 * [com.convoscribe.recording.RecordingState.inputLevel]). Discrete
 * blocks instead of a smooth bar: the meter updates once per audio
 * chunk, and stepped changes refresh cleanly on e-ink.
 */
@Composable
fun InputLevelMeter(level: Float, modifier: Modifier = Modifier) {
    val lit = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme
    val unlit = if (AppTheme.isEink) scheme.outlineVariant else scheme.surfaceVariant
    val gap = AppTheme.spacing.xxs
    val description = stringResource(R.string.record_input_level)
    val clamped = level.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.levelMeterHeight)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            }
    ) {
        val gapPx = gap.toPx()
        val segmentWidth = (size.width - gapPx * (SEGMENTS - 1)) / SEGMENTS
        val litCount = (clamped * SEGMENTS).roundToInt()
        val radius = CornerRadius(size.height / 4f)
        for (i in 0 until SEGMENTS) {
            drawRoundRect(
                color = if (i < litCount) lit else unlit,
                topLeft = Offset(i * (segmentWidth + gapPx), 0f),
                size = Size(segmentWidth, size.height),
                cornerRadius = radius
            )
        }
    }
}

private const val SEGMENTS = 24
