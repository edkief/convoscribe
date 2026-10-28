package com.convoscribe.ui.session

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.data.MessageRole
import com.convoscribe.ui.components.EmptyState

/**
 * Ask tab body:
 *
 *  - the LLM endpoint is not configured: an empty state that opens
 *    Settings;
 *  - configured: the questions and answers as chat bubbles in an
 *    [AskConversation], each answer about a long transcript noting how
 *    much of it the answer saw (issue #28).
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
        AskNotConfigured(onOpenSettings)
        return
    }
    AskConversation(
        messageCount = state.messages.size,
        streaming = state.streaming,
        running = state.running,
        error = state.error,
        inputLabel = stringResource(R.string.session_ask_input_label),
        onAsk = onAsk,
        onRetry = onRetry,
        onCancel = onCancel,
        empty = {
            EmptyState(
                icon = painterResource(R.drawable.ic_conversation),
                title = stringResource(R.string.session_ask_empty_title),
                body = stringResource(R.string.session_ask_empty)
            )
        }
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
    }
}
