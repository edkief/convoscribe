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
 * identifying speakers, speakers still to identify, or speaker
 * detection failed. Draws nothing for
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
    val dotColor = when (status) {
        SessionStatus.DIARIZING -> content
        // Waiting, not wrong: a neutral dot (issue #11).
        SessionStatus.DIARIZATION_PENDING -> scheme.outline
        else -> scheme.error
    }
    Pill(label = label, container = container, content = content, dotColor = dotColor, modifier = modifier)
}

/**
 * Pill for a session's place in sync (issue #69): its recording still
 * downloading, or the session removed from this device and kept only
 * on the sync server. Neutral colours: nothing is wrong.
 */
@Composable
fun SyncBadge(onServerOnly: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Pill(
        label = stringResource(if (onServerOnly) R.string.sync_badge_on_server else R.string.sync_badge_downloading),
        container = scheme.secondaryContainer,
        content = scheme.onSecondaryContainer,
        dotColor = scheme.outline,
        modifier = modifier
    )
}

/**
 * Pill for a recording that is paused (issue #7): the session is
 * still open but nothing is being captured. Neutral colours with a
 * hollow-looking dot, so it reads differently from Recording.
 */
@Composable
fun PausedBadge(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Pill(
        label = stringResource(R.string.status_paused),
        container = scheme.secondaryContainer,
        content = scheme.onSecondaryContainer,
        dotColor = scheme.outline,
        modifier = modifier
    )
}

/** The status pill: a dot and a label, outlined instead of filled on e-ink. */
@Composable
private fun Pill(label: String, container: Color, content: Color, dotColor: Color, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val eink = AppTheme.isEink
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
    SessionStatus.DIARIZATION_PENDING -> stringResource(R.string.status_pending)
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
