package com.convoscribe.ui.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.convoscribe.R
import com.convoscribe.data.TalkingPointEntity
import com.convoscribe.data.TalkingPointList
import com.convoscribe.ui.components.AddTalkingPointField
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.PastePointsDialog
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.TalkingPointRow
import com.convoscribe.ui.theme.AppTheme

/** What the Record screen's talking points can do (issue #102). */
internal data class TalkingPointActions(
    val onAttach: (TalkingPointList) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    /** How many points pasting a block would add (issue #114); the block itself goes to [onAdd]. */
    val pasteCount: (String) -> Int = { 0 },
    val onRemove: (Int) -> Unit = {},
    val onToggleLive: (TalkingPointEntity) -> Unit = {},
    val onAddLive: (String) -> Unit = {},
    val onOpenFirstChange: (Boolean) -> Unit = {}
)

/**
 * [points] as the open session's card lists them: in their own order,
 * or with [openFirst] those not covered yet first, each group keeping
 * that order.
 */
internal fun livePointsOrder(points: List<TalkingPointEntity>, openFirst: Boolean): List<TalkingPointEntity> =
    if (openFirst) points.sortedBy { it.covered } else points

/**
 * Before Start (issue #102): the new conversation's talking points,
 * *Attach a list* when there are lists, *Paste points* (issue #114) and
 * a field to add one, which splits text pasted over several lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TalkingPointsSetup(points: List<String>, lists: List<TalkingPointList>, actions: TalkingPointActions) {
    var pasting by rememberSaveable { mutableStateOf(false) }
    if (pasting) {
        PastePointsDialog(
            countFor = actions.pasteCount,
            onImport = {
                actions.onAdd(it)
                pasting = false
            },
            onDismiss = { pasting = false }
        )
    }
    SectionCard(
        title = stringResource(R.string.talking_points_title),
        supportingText = stringResource(R.string.record_talking_points_help)
    ) {
        // Wraps so Paste points moves under Attach a list at large font sizes.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
        ) {
            if (lists.isNotEmpty()) AttachListButton(lists = lists, onAttach = actions.onAttach)
            OutlinedButton(onClick = { pasting = true }) {
                Text(text = stringResource(R.string.talking_points_paste))
            }
        }
        points.forEachIndexed { index, point ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = point, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.onRemove(index) }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.record_talking_point_remove, point)
                    )
                }
            }
        }
        AddTalkingPointField(onAdd = actions.onAdd, splitLines = true)
    }
}

@Composable
private fun AttachListButton(lists: List<TalkingPointList>, onAttach: (TalkingPointList) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(text = stringResource(R.string.record_talking_points_attach)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            lists.forEach { list ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text = list.name)
                            Text(
                                text = pluralStringResource(
                                    R.plurals.talking_points_count,
                                    list.points.size,
                                    list.points.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onAttach(list)
                    }
                )
            }
        }
    }
}

/**
 * While a session is open (issue #102): *Talking points, n of m
 * covered*, which folds away, then each point to tick and a field to add one. Kept
 * short so the live transcript keeps most of the screen.
 */
@Composable
internal fun LiveTalkingPoints(
    points: List<TalkingPointEntity>,
    onToggle: (TalkingPointEntity) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
    checksFailing: Boolean = false,
    openFirst: Boolean = false,
    onOpenFirstChange: (Boolean) -> Unit = {}
) {
    val spacing = AppTheme.spacing
    // Open while there are points; a conversation without any starts folded.
    var expanded by rememberSaveable(points.isNotEmpty()) { mutableStateOf(points.isNotEmpty()) }
    val covered = points.count { it.covered }
    val state =
        stringResource(
            if (expanded) R.string.record_talking_points_expanded else R.string.record_talking_points_collapsed
        )
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.record_talking_points_progress, covered, points.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics {
                    heading()
                    stateDescription = state
                }
                .padding(horizontal = spacing.l, vertical = spacing.m)
        )
        if (checksFailing) {
            // Quiet on purpose: checks may run every few seconds, and recording goes on regardless.
            SupportingText(
                text = stringResource(R.string.record_talking_points_check_failing),
                modifier = Modifier.padding(start = spacing.l, end = spacing.l, bottom = spacing.s)
            )
        }
        if (expanded && points.size > 1) {
            // Outside the scrolling list, so it stays in reach with a dozen points.
            FilterChip(
                selected = openFirst,
                onClick = { onOpenFirstChange(!openFirst) },
                label = { Text(text = stringResource(R.string.record_talking_points_open_first)) },
                leadingIcon = if (openFirst) {
                    { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                modifier = Modifier.padding(start = spacing.l, end = spacing.l)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = spacing.s, end = spacing.l, bottom = spacing.m),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                if (points.isEmpty()) {
                    SupportingText(
                        text = stringResource(R.string.record_talking_points_none),
                        modifier = Modifier.padding(start = spacing.s)
                    )
                }
                livePointsOrder(points, openFirst).forEach { point ->
                    TalkingPointRow(
                        text = point.text,
                        covered = point.covered,
                        coveredAtMs = point.coveredAtMs,
                        onCheckedChange = { onToggle(point) }
                    )
                }
                AddTalkingPointField(onAdd = onAdd, modifier = Modifier.padding(start = spacing.s))
            }
        }
    }
}
