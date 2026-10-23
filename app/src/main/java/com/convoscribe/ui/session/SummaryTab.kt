package com.convoscribe.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.convoscribe.R
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptMark
import com.convoscribe.llm.MarkNotesProgress
import com.convoscribe.llm.SummaryStep
import com.convoscribe.ui.asString
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.templates.SummaryTemplatePicker
import com.convoscribe.ui.theme.AppTheme

/**
 * Summary tab body:
 *
 *  - the LLM endpoint is not configured: an empty state that opens
 *    Settings;
 *  - nothing saved yet: an empty state with Summarise;
 *  - running: Stop with a spinner, and the summary as it streams in;
 *    a long transcript summarised in parts (issue #28) also shows the
 *    step it is on ("Summarising part 2 of 5…");
 *  - saved: Regenerate and Copy above the summary in a card, with
 *    the name of the template it was written with.
 *
 * Above every state but the first, a template picker (issue #27)
 * chooses the template the next Summarise or Regenerate uses.
 *
 * A failure shows an error banner with Retry. The text is selectable;
 * Copy puts all of it on the clipboard. The LLM's raw text is shown
 * verbatim (no markdown rendering).
 *
 * A session with [marks] also gets a Notes section below the summary
 * (issue #48), and skips the empty state so its notes show without a
 * summary; see [MarkNotesSection].
 */
@Composable
internal fun SummaryTab(
    state: SummaryUiState,
    onSummarise: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectTemplate: (String) -> Unit = {},
    marks: List<TranscriptMark> = emptyList(),
    onWriteNotes: () -> Unit = {},
    onCancelNotes: () -> Unit = {},
    onOpenMark: (TranscriptMark) -> Unit = {}
) {
    if (!state.configured) {
        EmptyState(
            icon = rememberVectorPainter(Icons.Filled.Settings),
            title = stringResource(R.string.session_summary_not_configured),
            actionLabel = stringResource(R.string.session_summary_open_settings),
            onAction = onOpenSettings
        )
        return
    }
    val saved = state.saved
    val text = if (state.running) state.streaming else saved.orEmpty()
    val spacing = AppTheme.spacing
    val picker: @Composable () -> Unit = {
        SummaryTemplatePicker(
            label = stringResource(R.string.session_summary_template_label),
            templates = state.templates,
            selectedKey = state.selectedKey,
            onSelect = onSelectTemplate,
            enabled = !state.running
        )
    }
    if (text.isBlank() && !state.running && state.error == null && marks.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .widthIn(max = AppTheme.sizes.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(start = spacing.screen, end = spacing.screen, top = spacing.l)
            ) {
                picker()
            }
            EmptyState(
                icon = painterResource(R.drawable.ic_conversation),
                title = stringResource(R.string.session_summary_empty),
                actionLabel = stringResource(R.string.session_summary_summarise),
                onAction = onSummarise,
                modifier = Modifier.weight(1f)
            )
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = AppTheme.sizes.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m)
        ) {
            picker()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.m)
            ) {
                if (state.running) {
                    OutlinedButton(onClick = onCancel) {
                        Text(text = stringResource(R.string.session_summary_stop))
                    }
                    InlineProgress()
                    state.step?.let { step ->
                        Text(
                            text = when (step) {
                                is SummaryStep.Part ->
                                    stringResource(R.string.session_summary_step_part, step.part, step.parts)
                                SummaryStep.Combining -> stringResource(R.string.session_summary_step_combining)
                                SummaryStep.Writing -> stringResource(R.string.session_summary_step_writing)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Button(onClick = onSummarise) {
                        val label = if (saved.isNullOrBlank()) {
                            R.string.session_summary_summarise
                        } else {
                            R.string.session_summary_regenerate
                        }
                        Text(text = stringResource(label))
                    }
                    if (!saved.isNullOrBlank()) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(saved)) }) {
                            Text(text = stringResource(R.string.session_summary_copy))
                        }
                    }
                }
            }
            state.error?.let { message ->
                ErrorBanner(
                    message = message.asString(),
                    actionLabel = stringResource(R.string.session_summary_retry),
                    onAction = onSummarise
                )
            }
            val templateName = state.savedTemplateName
            if (!state.running && !saved.isNullOrBlank() && templateName != null) {
                Text(
                    text = stringResource(R.string.session_summary_template_used, templateName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (text.isNotBlank()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = AppTheme.typography.transcript,
                            modifier = Modifier.padding(spacing.l)
                        )
                    }
                }
            }
            if (marks.isNotEmpty()) {
                MarkNotesSection(
                    marks = marks,
                    progress = state.notes,
                    onWrite = onWriteNotes,
                    onCancel = onCancelNotes,
                    onOpenMark = onOpenMark
                )
            }
        }
    }
}

/**
 * The full notes of the session's marks (issue #48): *Write notes* or
 * *Regenerate notes* (Stop and the note being written while it runs),
 * a failure with Retry and Dismiss, then a card per mark with its time,
 * the user's note and the full note. Tapping the time opens the
 * transcript there. A card changes once, when its note is complete,
 * so an e-ink screen is not redrawn word by word.
 */
@Composable
private fun MarkNotesSection(
    marks: List<TranscriptMark>,
    progress: MarkNotesProgress?,
    onWrite: () -> Unit,
    onCancel: () -> Unit,
    onOpenMark: (TranscriptMark) -> Unit
) {
    val spacing = AppTheme.spacing
    Text(
        text = stringResource(R.string.session_notes_title, marks.size),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = spacing.s)
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
        if (progress?.running == true) {
            OutlinedButton(onClick = onCancel) {
                Text(text = stringResource(R.string.session_summary_stop))
            }
            InlineProgress()
            Text(
                text = stringResource(
                    R.string.session_notes_writing,
                    (progress.done + 1).coerceAtMost(progress.total),
                    progress.total
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedButton(onClick = onWrite) {
                val label = if (marks.any { it.expandedNote != null }) {
                    R.string.session_notes_regenerate
                } else {
                    R.string.session_notes_write
                }
                Text(text = stringResource(label))
            }
        }
    }
    progress?.error?.let { message ->
        ErrorBanner(
            message = message,
            actionLabel = stringResource(R.string.session_summary_retry),
            onAction = onWrite,
            secondaryActionLabel = stringResource(R.string.session_notes_dismiss),
            onSecondaryAction = onCancel
        )
    }
    marks.forEach { mark -> MarkNoteCard(mark = mark, onOpen = { onOpenMark(mark) }) }
}

/** One mark's card in [MarkNotesSection]. */
@Composable
private fun MarkNoteCard(mark: TranscriptMark, onOpen: () -> Unit) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.l), verticalArrangement = Arrangement.spacedBy(spacing.s)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen),
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
                Text(
                    text = mark.note ?: stringResource(R.string.session_mark_no_note),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (mark.note == null) null else FontWeight.SemiBold,
                    color = if (mark.note == null) scheme.onSurfaceVariant else scheme.onSurface
                )
            }
            val full = mark.expandedNote
            if (full == null) {
                Text(
                    text = stringResource(R.string.session_notes_not_written),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    Text(text = full, style = AppTheme.typography.transcript)
                }
                if (mark.expandedStale) {
                    Text(
                        text = stringResource(R.string.session_notes_outdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
