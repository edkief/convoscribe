package com.convoscribe.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convoscribe.R
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptMark
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.MarkNoteDialog
import com.convoscribe.ui.theme.AppTheme

/**
 * What the session screen can do with a mark (issue #30). Seeking is
 * [PlaybackActions.onSeek]; these are the edits. [SessionScreen] fills
 * them from the ViewModel.
 */
internal class MarkActions(
    val onEditNote: (TranscriptMark, String) -> Unit = { _, _ -> },
    val onDelete: (TranscriptMark) -> Unit = {}
)

/**
 * A mark's flag in the transcript, above the line being spoken when it
 * was taken: the flag, its time and its note. Tapping it seeks there
 * when [onTapped] is set (the recording can be played).
 */
@Composable
internal fun MarkFlag(mark: TranscriptMark, onTapped: (() -> Unit)?) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    var modifier = Modifier.fillMaxWidth()
    if (onTapped != null) modifier = modifier.clickable(role = Role.Button, onClick = onTapped)
    Row(
        modifier = modifier.padding(horizontal = spacing.s, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_flag),
            contentDescription = stringResource(R.string.session_mark),
            tint = scheme.primary,
            modifier = Modifier.size(AppTheme.sizes.speakerDot * 2)
        )
        Text(
            text = TranscriptFormatter.timestamp(mark.atMs),
            style = AppTheme.typography.figures,
            color = scheme.primary
        )
        mark.note?.let {
            Text(
                text = it,
                style = AppTheme.typography.transcriptMeta,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The *Marks* list: every mark with its time, note and the start of
 * its full note (issue #48). Tapping one
 * seeks there and closes the list ([onSeek] `null` when the recording
 * cannot be played); each row can have its note edited or be deleted.
 */
@Composable
internal fun MarksDialog(
    marks: List<TranscriptMark>,
    onSeek: ((TranscriptMark) -> Unit)?,
    actions: MarkActions,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<TranscriptMark?>(null) }
    var deleting by remember { mutableStateOf<TranscriptMark?>(null) }
    val spacing = AppTheme.spacing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_marks_title)) },
        text = {
            if (marks.isEmpty()) {
                Text(text = stringResource(R.string.session_marks_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = MARKS_LIST_MAX_HEIGHT)) {
                    items(items = marks, key = { it.id }) { mark ->
                        var row = Modifier.fillMaxWidth()
                        if (onSeek != null) {
                            row = row.clickable(role = Role.Button) {
                                onSeek(mark)
                                onDismiss()
                            }
                        }
                        Row(
                            modifier = row.padding(vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.s)
                        ) {
                            Text(
                                text = TranscriptFormatter.timestamp(mark.atMs),
                                style = AppTheme.typography.figures,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mark.note ?: stringResource(R.string.session_mark_no_note),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (mark.note == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // The full note (issue #48), shortened; the Summary tab has all of it.
                                mark.expandedNote?.let { full ->
                                    Text(
                                        text = full,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { editing = mark }) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.session_mark_edit_note)
                                )
                            }
                            IconButton(
                                onClick = { deleting = mark },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.session_mark_delete)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_marks_close))
            }
        }
    )
    editing?.let { mark ->
        MarkNoteDialog(
            time = TranscriptFormatter.timestamp(mark.atMs),
            initialNote = mark.note,
            onConfirm = { note ->
                editing = null
                actions.onEditNote(mark, note)
            },
            onDismiss = { editing = null }
        )
    }
    deleting?.let { mark ->
        ConfirmDialog(
            title = stringResource(R.string.session_mark_delete_title),
            message = stringResource(R.string.session_mark_delete_message, TranscriptFormatter.timestamp(mark.atMs)),
            confirmLabel = stringResource(R.string.session_mark_delete),
            dismissLabel = stringResource(R.string.session_rename_cancel),
            destructive = true,
            onConfirm = {
                deleting = null
                actions.onDelete(mark)
            },
            onDismiss = { deleting = null }
        )
    }
}

/**
 * Draw a tick across the seek bar's track for each of [marks] in
 * `0..durationMs`. The ticks reach a little past the track so they show
 * on its filled and unfilled parts alike; nothing animates.
 */
internal fun DrawScope.drawMarkTicks(marks: List<TranscriptMark>, durationMs: Long, color: Color) {
    if (durationMs <= 0L) return
    val stroke = MARK_TICK_WIDTH.toPx()
    val overhang = MARK_TICK_OVERHANG.toPx()
    for (mark in marks) {
        val fraction = (mark.atMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val x = fraction * size.width
        drawLine(color, Offset(x, -overhang), Offset(x, size.height + overhang), strokeWidth = stroke)
    }
}

private val MARK_TICK_WIDTH = 2.dp
private val MARK_TICK_OVERHANG = 4.dp
private val MARKS_LIST_MAX_HEIGHT = 360.dp
