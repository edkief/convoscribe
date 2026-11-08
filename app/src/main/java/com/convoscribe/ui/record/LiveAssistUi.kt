package com.convoscribe.ui.record

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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.convoscribe.R
import com.convoscribe.llm.AssistCard
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

/** The status line for [status] and whether it is [enabled], or `null` for none. */
internal fun assistStatusText(status: AssistStatus, enabled: Boolean): Int? = when (status) {
    AssistStatus.CHECKING -> R.string.record_assist_checking
    AssistStatus.UNREACHABLE -> R.string.record_assist_unreachable
    AssistStatus.PAUSED -> R.string.record_assist_paused
    AssistStatus.IDLE -> if (enabled) R.string.record_assist_listening else null
}

/** Where [cite] points: `title — date · mm:ss`, without the time when the line is not known. */
internal fun citeText(cite: Citation): String =
    "${cite.title} — ${cite.date}" + (cite.atMs?.let { " · " + formatElapsed(it) } ?: "")

/**
 * While a session is open (issue #50): the *Live assist* switch, which
 * says that recent speech is sent to the language model, a single
 * status line, and the answer cards, newest on top. A card is complete
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
    assistStatusText(state.status, state.enabled)?.let {
        SupportingText(
            text = stringResource(it),
            modifier = Modifier.padding(start = spacing.l, end = spacing.l, bottom = spacing.s)
        )
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
