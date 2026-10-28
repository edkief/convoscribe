package com.convoscribe.ui.session

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.convoscribe.R
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.MarkdownText
import com.convoscribe.ui.theme.AppTheme

/** The empty state shown in place of a chat while no LLM endpoint is set up; it opens Settings. */
@Composable
internal fun AskNotConfigured(onOpenSettings: () -> Unit) {
    EmptyState(
        icon = rememberVectorPainter(Icons.Filled.Settings),
        title = stringResource(R.string.session_ask_not_configured),
        actionLabel = stringResource(R.string.session_ask_open_settings),
        onAction = onOpenSettings
    )
}

/**
 * A chat with the LLM, shared by the Ask tab and *Ask across sessions*
 * (issue #49): the [messages] (the caller's [ChatBubble] items, oldest
 * first, [messageCount] of them), the in-flight answer streaming into a
 * live bubble, a typing bubble until its first words arrive, an error
 * banner with Retry when the last call failed, and the question field
 * with a Send button (Stop while an answer is streaming) at the bottom.
 * [empty] stands in for the list before the first question.
 *
 * Whenever the conversation grows (a new bubble, or the streaming
 * answer gains tokens) the list jumps to the newest bubble.
 */
@Composable
internal fun AskConversation(
    messageCount: Int,
    streaming: String,
    running: Boolean,
    error: String?,
    inputLabel: String,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit,
    messages: LazyListScope.() -> Unit
) {
    // Cleared when a round starts (a new question was added or
    // `running` turned on), and kept while the streaming bubble grows.
    var draft by rememberSaveable(running, messageCount) { mutableStateOf("") }
    val send: () -> Unit = {
        val text = draft.trim()
        if (text.isNotEmpty() && !running) {
            onAsk(text)
            draft = ""
        }
    }
    val spacing = AppTheme.spacing

    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        val listState = rememberLazyListState()
        // Between Send and the first token (issue #54).
        val waiting = running && streaming.isEmpty()
        val hasContent = messageCount > 0 || streaming.isNotEmpty() || waiting
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
                    messages()
                    if (streaming.isNotEmpty()) {
                        item(key = "streaming") { ChatBubble(text = streaming, isUser = false) }
                    }
                    if (waiting) {
                        item(key = "typing") { TypingBubble() }
                    }
                }
                LaunchedEffect(messageCount, streaming, waiting) {
                    val live = streaming.isNotEmpty() || waiting
                    val last = messageCount + (if (live) 1 else 0) - 1
                    if (last >= 0) listState.scrollToItem(last)
                }
            } else {
                empty()
            }
        }

        error?.let { message ->
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
                enabled = !running,
                placeholder = { Text(text = inputLabel) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            if (running) {
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
 * The text is selectable so a phrase can be copied; answers are
 * rendered as Markdown (issue #53), questions as typed. [note] is a small
 * line under the text: for an answer about a long transcript
 * (issue #28), how much of the conversation it was based on. [footer]
 * goes under it: the citations of an answer across sessions (issue #49).
 */
@Composable
internal fun ChatBubble(text: String, isUser: Boolean, note: String? = null, footer: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    BubbleFrame(isUser = isUser) {
        Column(modifier = Modifier.padding(horizontal = AppTheme.spacing.m, vertical = AppTheme.spacing.s)) {
            SelectionContainer {
                if (isUser) {
                    Text(text = text, style = MaterialTheme.typography.bodyLarge)
                } else {
                    MarkdownText(text = text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppTheme.spacing.xs)
                )
            }
            footer?.let { content ->
                Box(modifier = Modifier.padding(top = AppTheme.spacing.xs)) { content() }
            }
        }
    }
}

/** A chat bubble's row alignment, colours, shape and e-ink outline, around [content]. */
@Composable
private fun BubbleFrame(isUser: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) scheme.primaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (isUser) scheme.onPrimaryContainer else scheme.onSurface,
            shape = MaterialTheme.shapes.large,
            border = if (AppTheme.isEink && !isUser) BorderStroke(AppTheme.sizes.outline, scheme.outline) else null,
            modifier = Modifier.widthIn(max = AppTheme.sizes.bubbleMaxWidth),
            content = content
        )
    }
}

/**
 * The answer's place while the model has not sent anything yet
 * (issue #54): three dots that pulse in turn. On e-ink they stay still,
 * next to "Generating answer…", so the panel is not redrawn every
 * frame. Screen readers hear the label once.
 */
@Composable
private fun TypingBubble() {
    val label = stringResource(R.string.session_ask_streaming)
    val spacing = AppTheme.spacing
    BubbleFrame(
        isUser = false,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.m, vertical = spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s)
        ) {
            TypingDots()
            if (AppTheme.reduceMotion) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val color = LocalContentColor.current
    val still = AppTheme.reduceMotion
    val transition = if (still) null else rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        repeat(TYPING_DOTS) { index ->
            val alpha = transition?.animateFloat(
                initialValue = TYPING_DIM,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = TYPING_PULSE_MS),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * TYPING_STAGGER_MS)
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(AppTheme.sizes.speakerDot)
                    .graphicsLayer { this.alpha = alpha?.value ?: 1f }
                    .background(color, CircleShape)
            )
        }
    }
}

private const val TYPING_DOTS = 3
private const val TYPING_DIM = 0.25f
private const val TYPING_PULSE_MS = 450
private const val TYPING_STAGGER_MS = 150
