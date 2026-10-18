package com.convoscribe.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.convoscribe.R
import com.convoscribe.ui.record.formatElapsed
import com.convoscribe.ui.theme.AppTheme

/**
 * Body of the floating recording control (issue #7), drawn over other
 * apps while a session is open: a status dot with "Recording" or
 * "Paused" and the recorded time, then Pause / Resume and Stop.
 * Tapping the status part opens the Record screen. Render-only, so the
 * screenshot tests can draw it; [FloatingControl] hosts it in its own
 * window and handles dragging.
 *
 * Nothing here animates. In e-ink mode the pill is outlined with no
 * shadow, like the app's other surfaces.
 */
@Composable
internal fun FloatingControlContent(
    paused: Boolean,
    elapsedMs: Long,
    onOpen: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = AppTheme.spacing
    val eink = AppTheme.isEink
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = scheme.surface,
        contentColor = scheme.onSurface,
        tonalElevation = if (eink) 0.dp else spacing.xs,
        shadowElevation = if (eink) 0.dp else spacing.s,
        border = BorderStroke(if (eink) AppTheme.sizes.activeOutline else AppTheme.sizes.outline, scheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val openLabel = stringResource(R.string.overlay_open)
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .padding(start = spacing.m, end = spacing.s, top = spacing.xs, bottom = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s)
            ) {
                Box(
                    modifier = Modifier
                        .size(AppTheme.sizes.speakerDot)
                        .background(color = if (paused) scheme.outline else scheme.error, shape = CircleShape)
                )
                Column {
                    Text(
                        text = stringResource(if (paused) R.string.status_paused else R.string.status_recording),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Text(text = formatElapsed(elapsedMs), style = AppTheme.typography.figures)
                }
            }
            IconButton(onClick = onPauseResume) {
                Icon(
                    painter = painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                    contentDescription = stringResource(if (paused) R.string.record_resume else R.string.record_pause)
                )
            }
            IconButton(
                onClick = onStop,
                colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.error)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = stringResource(R.string.record_stop)
                )
            }
        }
    }
}
