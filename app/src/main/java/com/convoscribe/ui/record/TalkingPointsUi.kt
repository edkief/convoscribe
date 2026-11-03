package com.convoscribe.ui.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.TalkingPointRow
import com.convoscribe.ui.theme.AppTheme

/** What the Record screen's talking points can do (issue #102). */
internal data class TalkingPointActions(
    val onAttach: (TalkingPointList) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onRemove: (Int) -> Unit = {},
    val onToggleLive: (TalkingPointEntity) -> Unit = {},
    val onAddLive: (String) -> Unit = {}
)

/**
 * Before Start (issue #102): the new conversation's talking points,
 * *Attach a list* when there are lists, and a field to add one.
 */
@Composable
internal fun TalkingPointsSetup(points: List<String>, lists: List<TalkingPointList>, actions: TalkingPointActions) {
    SectionCard(
        title = stringResource(R.string.talking_points_title),
        supportingText = stringResource(R.string.record_talking_points_help)
    ) {
        if (lists.isNotEmpty()) AttachListButton(lists = lists, onAttach = actions.onAttach)
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
        AddTalkingPointField(onAdd = actions.onAdd)
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
    modifier: Modifier = Modifier
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
                points.forEach { point ->
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
