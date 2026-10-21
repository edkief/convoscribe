package com.convoscribe.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.convoscribe.R
import com.convoscribe.data.MessageRole
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.theme.AppTheme

/**
 * Ask tab body:
 *
 *  - the LLM endpoint is not configured: an empty state that opens
 *    Settings;
 *  - configured: the questions and answers as chat bubbles (the
 *    in-flight answer streams into a live bubble), an error banner with
 *    Retry when the last call failed, and the question field with a
 *    Send button (Stop while an answer is streaming) at the bottom.
 *
 * Whenever the conversation grows (a new bubble, or the streaming
 * answer gains tokens) the list jumps to the newest bubble.
 */
@Composable
internal fun AskTab(
    state: AskUiState,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (!state.configured) {
        EmptyState(
            icon = rememberVectorPainter(Icons.Filled.Settings),
            title = stringResource(R.string.session_ask_not_configured),
            actionLabel = stringResource(R.string.session_ask_open_settings),
            onAction = onOpenSettings
        )
        return
    }
    // Cleared when a round starts (a new question was added or
    // `running` turned on), and kept while the streaming bubble grows.
    var draft by rememberSaveable(state.running, state.messages.size) { mutableStateOf("") }
    val send: () -> Unit = {
        val text = draft.trim()
        if (text.isNotEmpty() && !state.running) {
            onAsk(text)
            draft = ""
        }
    }
    val spacing = AppTheme.spacing

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        val listState = rememberLazyListState()
        val hasContent = state.messages.isNotEmpty() || state.streaming.isNotEmpty()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (hasContent) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.m),
                    verticalArrangement = Arrangement.spacedBy(spacing.s)
                ) {
                    items(items = state.messages, key = { it.id }) { message ->
                        val covered = message.coveredMs
                        val total = message.totalMs
                        val coverage = if (covered != null && total != null) {
                            val (coveredMin, totalMin) = coverageMinutes(covered, total)
                            pluralStringResource(R.plurals.session_ask_coverage, totalMin, coveredMin, totalMin)
                        } else {
                            null
                        }
                        ChatBubble(text = message.content, isUser = message.role == MessageRole.USER, note = coverage)
                    }
                    if (state.streaming.isNotEmpty()) {
                        item(key = "streaming") { ChatBubble(text = state.streaming, isUser = false) }
                    }
                }
                LaunchedEffect(state.messages.size, state.streaming) {
                    val last = state.messages.size + (if (state.streaming.isNotEmpty()) 1 else 0) - 1
                    if (last >= 0) listState.scrollToItem(last)
                }
            } else {
                EmptyState(
                    icon = painterResource(R.drawable.ic_conversation),
                    title = stringResource(R.string.session_ask_empty_title),
                    body = stringResource(R.string.session_ask_empty)
                )
            }
        }

        state.error?.let { message ->
            ErrorBanner(
                message = message,
                actionLabel = stringResource(R.string.session_ask_retry),
                onAction = onRetry,
                modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs)
            )
        }

        Row(
            modifier = Modifier
                .widthIn(max = AppTheme.sizes.contentMaxWidth)
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = spacing.screen, vertical = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !state.running,
                placeholder = { Text(text = stringResource(R.string.session_ask_input_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            if (state.running) {
                FilledIconButton(onClick = onCancel) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop),
                        contentDescription = stringResource(R.string.session_summary_stop)
                    )
                }
            } else {
                FilledIconButton(onClick = send, enabled = draft.isNotBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.session_ask_send_cd)
                    )
                }
            }
        }
    }
}

/**
 * One chat bubble: questions on the right in the primary container
 * colour, answers on the left on a quiet surface (outlined on e-ink).
 * The text is selectable so a phrase can be copied. [note] is a small
 * line under the text: for an answer about a long transcript
 * (issue #28), how much of the conversation it was based on.
 */
@Composable
private fun ChatBubble(text: String, isUser: Boolean, note: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val eink = AppTheme.isEink
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) scheme.primaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (isUser) scheme.onPrimaryContainer else scheme.onSurface,
            shape = MaterialTheme.shapes.large,
            border = if (eink && !isUser) BorderStroke(AppTheme.sizes.outline, scheme.outline) else null,
            modifier = Modifier.widthIn(max = AppTheme.sizes.bubbleMaxWidth)
        ) {
            Column(modifier = Modifier.padding(horizontal = AppTheme.spacing.m, vertical = AppTheme.spacing.s)) {
                SelectionContainer {
                    Text(text = text, style = MaterialTheme.typography.bodyLarge)
                }
                note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AppTheme.spacing.xs)
                    )
                }
            }
        }
    }
}
