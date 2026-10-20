package com.convoscribe.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.AnnotatedString
import com.convoscribe.R
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
 *  - saved: Regenerate and Copy above the summary in a card, with
 *    the name of the template it was written with.
 *
 * Above every state but the first, a template picker (issue #27)
 * chooses the template the next Summarise or Regenerate uses.
 *
 * A failure shows an error banner with Retry. The text is selectable;
 * Copy puts all of it on the clipboard. The LLM's raw text is shown
 * verbatim (no markdown rendering).
 */
@Composable
internal fun SummaryTab(
    state: SummaryUiState,
    onSummarise: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectTemplate: (String) -> Unit = {}
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
    if (text.isBlank() && !state.running && state.error == null) {
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
        }
    }
}
