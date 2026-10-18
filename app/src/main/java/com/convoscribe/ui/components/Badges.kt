package com.convoscribe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.convoscribe.R
import com.convoscribe.data.SessionStatus
import com.convoscribe.ui.theme.AppTheme

/**
 * Pill showing a session that is not simply finished: recording,
 * identifying speakers, or speaker detection failed. Draws nothing for
 * finished sessions. The text carries the meaning; colour only
 * reinforces it (and is reduced to an outline on e-ink).
 */
@Composable
fun StatusBadge(status: SessionStatus, modifier: Modifier = Modifier) {
    val label = statusLabel(status) ?: return
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        SessionStatus.RECORDING -> scheme.errorContainer to scheme.onErrorContainer
        SessionStatus.DIARIZATION_FAILED -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    val eink = AppTheme.isEink
    val dotColor = if (status == SessionStatus.DIARIZING) content else scheme.error
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (eink) scheme.surface else container,
        contentColor = content,
        border = if (eink) BorderStroke(AppTheme.sizes.outline, scheme.outline) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s, vertical = AppTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
        ) {
            Dot(color = dotColor)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** User-facing word for [status], or `null` for a finished session. */
@Composable
fun statusLabel(status: SessionStatus): String? = when (status) {
    SessionStatus.RECORDING -> stringResource(R.string.status_recording)
    SessionStatus.DIARIZING -> stringResource(R.string.status_diarizing)
    SessionStatus.DIARIZATION_FAILED -> stringResource(R.string.status_failed)
    SessionStatus.TRANSCRIBED, SessionStatus.DIARIZED -> null
}

/** The colour dot that marks a speaker, keyed on the speaker index. */
@Composable
fun SpeakerDot(speakerIndex: Int, modifier: Modifier = Modifier) {
    Dot(color = AppTheme.speakerColors[speakerIndex], modifier = modifier, size = AppTheme.sizes.speakerDot)
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = AppTheme.sizes.speakerDot) {
    Box(
        modifier = modifier
            .size(size)
            .background(color = color, shape = CircleShape)
    )
}
