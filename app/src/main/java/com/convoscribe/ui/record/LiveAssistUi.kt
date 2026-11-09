package com.convoscribe.ui.record

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.convoscribe.R
import com.convoscribe.llm.AssistCard
import com.convoscribe.llm.AssistMissReason
import com.convoscribe.llm.AssistStatus
import com.convoscribe.llm.Citation
import com.convoscribe.llm.LiveAssistState
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme

/** What the Record screen's live assist can do (issue #50). */
internal data class LiveAssistActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onPin: (Long) -> Unit = {},
    val onDismiss: (Long) -> Unit = {}
)

/**
 * The panel's one status line: [text], with [question] in it when set
 * (issue #152). [answering] lines stand out: they say a question was
 * heard and an answer is on its way.
 */
internal data class AssistStatusLine(@StringRes val text: Int, val question: String? = null) {
    val answering: Boolean get() = text == R.string.record_assist_answering
}

/**
 * The status line of [state], or `null` for none: what is wrong with
 * the endpoint first, then the question being answered, then the last
 * one that got no card and why (issue #152), then that it listens.
 */
internal fun assistStatusLine(state: LiveAssistState): AssistStatusLine? = when (state.status) {
    AssistStatus.CHECKING -> AssistStatusLine(R.string.record_assist_checking)
    AssistStatus.UNREACHABLE -> AssistStatusLine(R.string.record_assist_unreachable)
    AssistStatus.PAUSED -> AssistStatusLine(R.string.record_assist_paused)
    AssistStatus.IDLE -> when {
        !state.enabled -> null
        state.answering != null -> AssistStatusLine(R.string.record_assist_answering, clipQuestion(state.answering))
        state.missed != null -> AssistStatusLine(
            when (state.missed.reason) {
                AssistMissReason.NO_ANSWER -> R.string.record_assist_no_answer
                AssistMissReason.FAILED -> R.string.record_assist_failed
            },
            clipQuestion(state.missed.question)
        )
        else -> AssistStatusLine(R.string.record_assist_listening)
    }
}

/** The longest question a status line quotes. */
internal const val STATUS_QUESTION_CHARS: Int = 80

/** [question] cut to [STATUS_QUESTION_CHARS] on a word, behind an ellipsis. */
internal fun clipQuestion(question: String): String {
    if (question.length <= STATUS_QUESTION_CHARS) return question
    val cut = question.substring(0, STATUS_QUESTION_CHARS)
    val space = cut.lastIndexOf(' ')
    return (if (space > STATUS_QUESTION_CHARS / 2) cut.substring(0, space) else cut).trimEnd() + "…"
}

/** Where [cite] points: `title — date · mm:ss`, without the time when the line is not known. */
internal fun citeText(cite: Citation): String =
    "${cite.title} — ${cite.date}" + (cite.atMs?.let { " · " + formatElapsed(it) } ?: "")

/**
 * While a session is open (issue #50): the *Live assist* switch, which
 * says that recent speech is sent to the language model, a single
 * status line (which also says which question is being answered, and
 * which got no card, issue #152), and the answer cards, newest on top. A card is complete
 * when it appears and nothing here animates, so e-ink redraws once per
 * card. The caller bounds its height (half the room at most, next to
 * the live transcript) and it scrolls within it; once on, the switch's
 * text shrinks to the one line that matters, that speech is sent.
 */
@Composable
internal fun LiveAssistPanel(state: LiveAssistState, actions: LiveAssistActions, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            AssistContent(state, actions)
        }
    }
}

@Composable
private fun AssistContent(state: LiveAssistState, actions: LiveAssistActions) {
    val spacing = AppTheme.spacing
    SwitchRow(
        label = stringResource(R.string.record_assist_label),
        help = stringResource(if (state.enabled) R.string.record_assist_help_on else R.string.record_assist_help),
        checked = state.enabled,
        onCheckedChange = actions.onEnabledChange,
        modifier = Modifier.padding(horizontal = spacing.l, vertical = spacing.s)
    )
    assistStatusLine(state)?.let { line ->
        val text = if (line.question != null) stringResource(line.text, line.question) else stringResource(line.text)
        // Polite live region: a screen reader says when a question is heard and how it went.
        val modifier = Modifier
            .padding(start = spacing.l, end = spacing.l, bottom = spacing.s)
            .semantics { liveRegion = LiveRegionMode.Polite }
        if (line.answering) {
            // Static text, no spinner: e-ink redraws once when it starts and once when it ends.
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier
            )
        } else {
            SupportingText(text = text, modifier = modifier)
        }
    }
    if (state.cards.isNotEmpty()) {
        Column(
            modifier = Modifier.padding(start = spacing.l, end = spacing.s, bottom = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.s)
        ) {
            state.cards.forEachIndexed { index, card ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(end = spacing.s))
                AssistCardRow(card = card, actions = actions)
            }
        }
    }
}

@Composable
private fun AssistCardRow(card: AssistCard, actions: LiveAssistActions) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Text(
            text = card.question,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = AppTheme.spacing.s)
                .semantics { heading() }
        )
        Text(
            text = card.answer,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = AppTheme.spacing.s)
        )
        card.cites.firstOrNull()?.let { cite ->
            Text(
                text = citeText(cite),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(end = AppTheme.spacing.s)
            )
        }
        Row {
            TextButton(onClick = { actions.onPin(card.id) }) {
                Text(text = stringResource(R.string.record_assist_pin))
            }
            TextButton(onClick = { actions.onDismiss(card.id) }) {
                Text(text = stringResource(R.string.record_assist_dismiss))
            }
        }
    }
}
