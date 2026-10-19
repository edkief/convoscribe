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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convoscribe.R
import com.convoscribe.settings.FloatingShape
import com.convoscribe.ui.record.formatElapsed
import com.convoscribe.ui.theme.AppTheme

/**
 * Body of the floating recording control (issues #7 and #21), drawn
 * over other apps while a session is open, in one of three shapes:
 *
 *  * [FloatingShape.MINIMAL]: the status dot, Pause / Resume and Stop.
 *  * [FloatingShape.STANDARD]: the dot with *Recording* or *Paused*
 *    and the recorded time, then the same buttons.
 *  * [FloatingShape.EXPANDED]: the standard row above the end of the
 *    transcript, in a card of fixed size so the window does not jump
 *    as sentences arrive.
 *
 * Every shape ends with an expand / collapse arrow that calls
 * [onShapeChange] with [FloatingShape.next]. Tapping the status part
 * opens the Record screen. Render-only, so the screenshot tests can
 * draw it; [FloatingControl] hosts it in its own window and handles
 * dragging.
 *
 * Nothing here animates. In e-ink mode the control is outlined with no
 * shadow, like the app's other surfaces.
 */
@Composable
internal fun FloatingControlContent(
    shape: FloatingShape,
    ui: FloatingControlUi,
    onOpen: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onShapeChange: (FloatingShape) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = AppTheme.spacing
    val eink = AppTheme.isEink
    val expanded = shape == FloatingShape.EXPANDED
    Surface(
        modifier = modifier,
        shape = if (expanded) MaterialTheme.shapes.extraLarge else CircleShape,
        color = scheme.surface,
        contentColor = scheme.onSurface,
        tonalElevation = if (eink) 0.dp else spacing.xs,
        shadowElevation = if (eink) 0.dp else spacing.s,
        border = BorderStroke(if (eink) AppTheme.sizes.activeOutline else AppTheme.sizes.outline, scheme.outline)
    ) {
        if (expanded) {
            Column(modifier = Modifier.width(AppTheme.sizes.floatingExpandedWidth)) {
                ControlRow(shape, ui, onOpen, onPauseResume, onStop, onShapeChange)
                Text(
                    text = ui.transcript ?: stringResource(R.string.overlay_listening),
                    modifier = Modifier.padding(start = spacing.m, end = spacing.m, bottom = spacing.m),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.transcript == null) scheme.onSurfaceVariant else scheme.onSurface,
                    minLines = TRANSCRIPT_LINES,
                    maxLines = TRANSCRIPT_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            ControlRow(shape, ui, onOpen, onPauseResume, onStop, onShapeChange)
        }
    }
}

@Composable
private fun ControlRow(
    shape: FloatingShape,
    ui: FloatingControlUi,
    onOpen: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onShapeChange: (FloatingShape) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = AppTheme.spacing
    val status = stringResource(if (ui.paused) R.string.status_paused else R.string.status_recording)
    Row(
        modifier = Modifier.padding(spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val openLabel = stringResource(R.string.overlay_open)
        val dot = @Composable {
            Box(
                modifier = Modifier
                    .size(AppTheme.sizes.speakerDot)
                    .background(color = if (ui.paused) scheme.outline else scheme.error, shape = CircleShape)
            )
        }
        if (shape == FloatingShape.MINIMAL) {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = AppTheme.sizes.touchTarget, minHeight = AppTheme.sizes.touchTarget)
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = status },
                contentAlignment = Alignment.Center
            ) { dot() }
        } else {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .padding(start = spacing.m, end = spacing.s, top = spacing.xs, bottom = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s)
            ) {
                dot()
                Column {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Text(text = formatElapsed(ui.elapsedMs ?: 0L), style = AppTheme.typography.figures)
                }
            }
        }
        IconButton(onClick = onPauseResume) {
            Icon(
                painter = painterResource(if (ui.paused) R.drawable.ic_play else R.drawable.ic_pause),
                contentDescription = stringResource(if (ui.paused) R.string.record_resume else R.string.record_pause)
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
        val collapse = shape == FloatingShape.EXPANDED
        IconButton(
            onClick = { onShapeChange(shape.next()) },
            colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant)
        ) {
            Icon(
                painter = painterResource(if (collapse) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = stringResource(
                    if (collapse) R.string.overlay_collapse else R.string.overlay_expand
                )
            )
        }
    }
}

private const val TRANSCRIPT_LINES = 3
