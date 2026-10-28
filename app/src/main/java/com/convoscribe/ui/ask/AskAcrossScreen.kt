package com.convoscribe.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.MessageRole
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.llm.Citation
import com.convoscribe.llm.embedding.SemanticSearch
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.search.DateRange
import com.convoscribe.ui.search.FilterRow
import com.convoscribe.ui.session.AskConversation
import com.convoscribe.ui.session.AskNotConfigured
import com.convoscribe.ui.session.ChatBubble
import com.convoscribe.ui.session.StreamPacer
import com.convoscribe.ui.sessionRoute
import com.convoscribe.ui.theme.AppTheme

/**
 * *Ask across sessions* (issue #49), reached from Home and Search: a
 * question about every past conversation, narrowed by the Search
 * screen's filter chips, answered from excerpts of the most relevant
 * ones. Tapping a citation opens that session's transcript at the
 * cited line with the player cued there, as a search hit does.
 */
@Composable
fun AskAcrossScreen(navController: NavHostController) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val streamIntervalMs = if (AppTheme.isEink) StreamPacer.EINK_INTERVAL_MS else 0L
    val vm: AskAcrossViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AskAcrossViewModel(
                    repository = container.sessionRepository,
                    search = container.sessionSearch,
                    chatClient = container.chatClient,
                    settings = container.settingsRepository,
                    semantic = SemanticSearch(container.database.semanticIndexDao(), container.textEmbedderFactory),
                    streamIntervalMs = streamIntervalMs
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    AskAcrossBody(
        state = state,
        onAsk = vm::ask,
        onRetry = vm::retryLast,
        onCancel = vm::cancel,
        onClearHistory = vm::clearHistory,
        onLanguage = vm::setLanguage,
        onDateRange = vm::setDateRange,
        onSpeaker = vm::setSpeaker,
        onTag = vm::setTag,
        onOpenCitation = { citation ->
            navController.navigate(
                sessionRoute(citation.sessionId, tab = 0, lineId = citation.segmentId, atMs = citation.atMs)
            )
        },
        onOpenSettings = { navController.navigate("settings") },
        onBack = { navController.popBackStack() }
    )
}

/**
 * Render-only body of [AskAcrossScreen], so screenshot tests can draw
 * it with a fake [state]: the filter chips, then the chat. Each answer
 * notes how many excerpts from how many sessions it was based on and
 * lists its citations; *Clear history* is in the overflow menu. While
 * some sessions within the filters are not indexed for semantic search
 * yet (issue #61), a line under the filters says so.
 */
@Composable
internal fun AskAcrossBody(
    state: AskAcrossUiState,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClearHistory: () -> Unit,
    onLanguage: (String?) -> Unit,
    onDateRange: (DateRange) -> Unit,
    onSpeaker: (String?) -> Unit,
    onTag: (Long?) -> Unit,
    onOpenCitation: (Citation) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    val spacing = AppTheme.spacing
    ScreenScaffold(
        title = stringResource(R.string.ask_across_title),
        onBack = onBack,
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.home_more_options)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.ask_across_clear)) },
                        enabled = state.messages.isNotEmpty() && !state.running,
                        onClick = {
                            menuOpen = false
                            clearOpen = true
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!state.configured) {
                AskNotConfigured(onOpenSettings)
                return@Column
            }
            Box(
                modifier = Modifier
                    .widthIn(max = AppTheme.sizes.contentMaxWidth)
                    .padding(horizontal = spacing.screen, vertical = spacing.s)
            ) {
                FilterRow(
                    filters = state.filters,
                    options = state.options,
                    onLanguage = onLanguage,
                    onDateRange = onDateRange,
                    onSpeaker = onSpeaker,
                    onTag = onTag
                )
            }
            if (state.unindexed > 0) {
                SupportingText(
                    text = pluralStringResource(R.plurals.ask_across_unindexed, state.unindexed, state.unindexed),
                    modifier = Modifier
                        .widthIn(max = AppTheme.sizes.contentMaxWidth)
                        .padding(horizontal = spacing.screen)
                )
            }
            AskConversation(
                messageCount = state.messages.size,
                streaming = state.streaming,
                running = state.running,
                error = state.error,
                inputLabel = stringResource(R.string.ask_across_input_label),
                onAsk = onAsk,
                onRetry = onRetry,
                onCancel = onCancel,
                empty = {
                    EmptyState(
                        icon = painterResource(R.drawable.ic_conversation),
                        title = stringResource(R.string.ask_across_empty_title),
                        body = stringResource(R.string.ask_across_empty)
                    )
                }
            ) {
                items(items = state.messages, key = { it.id }) { message ->
                    val isUser = message.role == MessageRole.USER
                    ChatBubble(
                        text = message.content,
                        isUser = isUser,
                        note = if (isUser) null else basedOn(message),
                        footer = if (isUser || message.citations.isEmpty()) {
                            null
                        } else {
                            {
                                Citations(
                                    citations = message.citations,
                                    sessionIds = state.sessionIds,
                                    onOpen = onOpenCitation
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (clearOpen) {
        ConfirmDialog(
            title = stringResource(R.string.ask_across_clear_title),
            message = stringResource(R.string.ask_across_clear_message),
            confirmLabel = stringResource(R.string.session_ask_clear_confirm),
            dismissLabel = stringResource(R.string.session_ask_clear_cancel),
            destructive = true,
            onConfirm = {
                onClearHistory()
                clearOpen = false
            },
            onDismiss = { clearOpen = false }
        )
    }
}

/**
 * *Based on N excerpts from M sessions*, with *matched by meaning* when
 * semantic search helped pick them (issue #61), or `null` for an answer
 * that saw none.
 */
@Composable
private fun basedOn(message: AskAcrossMessage): String? {
    val excerpts = message.excerptCount ?: return null
    val sessions = message.sessionCount ?: return null
    if (excerpts == 0) return stringResource(R.string.ask_across_based_on_nothing)
    val from = pluralStringResource(R.plurals.ask_across_sessions, sessions, sessions)
    val based = pluralStringResource(R.plurals.ask_across_based_on, excerpts, excerpts, from)
    return if (message.semantic) stringResource(R.string.ask_across_matched_by_meaning, based) else based
}

/**
 * An answer's citations as chips: `title · date · mm:ss`, opening the
 * session at that line. A session deleted since is shown as such and
 * cannot be opened; while [sessionIds] is not known yet every chip is
 * shown as openable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Citations(citations: List<Citation>, sessionIds: Set<Long>?, onOpen: (Citation) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        citations.forEach { citation ->
            val exists = sessionIds == null || citation.sessionId in sessionIds
            val place = listOfNotNull(
                citation.title,
                citation.date,
                citation.atMs?.let { TranscriptFormatter.timestamp(it) }
            ).joinToString(" · ")
            val label = if (exists) place else stringResource(R.string.ask_across_session_deleted, place)
            AssistChip(
                onClick = { onOpen(citation) },
                enabled = exists,
                label = { Text(text = label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}
