package com.convoscribe.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.convoscribe.R
import com.convoscribe.data.ActionItemEntity
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.ui.actions.ActionItemIntents
import com.convoscribe.ui.theme.AppTheme

/**
 * One action item (issue #31): a checkbox, the text (struck through
 * once done), then who, by when and the moment it was agreed, and a
 * menu with Edit, Share, Add to calendar and Delete.
 *
 * Tapping the time calls [onOpenSource]; without it the time is plain
 * text. With [onOpen] the text opens the item where it came from (the
 * *Open actions* list). [context] is an extra first line of detail,
 * such as the session's title.
 */
@Composable
fun ActionItemRow(
    item: ActionItemEntity,
    onCheckedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onAddToCalendar: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSource: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    context: String? = null
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(checked = item.done, onCheckedChange = onCheckedChange)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = spacing.m)
                .let { if (onOpen != null) it.clickable(role = Role.Button, onClick = onOpen) else it },
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            context?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) scheme.onSurfaceVariant else scheme.onSurface,
                textDecoration = if (item.done) TextDecoration.LineThrough else null
            )
            val details = listOfNotNull(
                item.owner,
                ActionItemIntents.dueLabel(item.dueDate)?.let { stringResource(R.string.action_item_due, it) }
            )
            val at = item.atMs
            if (details.isNotEmpty() || at != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    if (at != null) {
                        val time = TranscriptFormatter.timestamp(at)
                        val label = stringResource(R.string.action_item_open_source, time)
                        Text(
                            text = time,
                            style = AppTheme.typography.figures,
                            color = if (onOpenSource != null) scheme.primary else scheme.onSurfaceVariant,
                            modifier = if (onOpenSource != null) {
                                Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onOpenSource)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.action_item_more)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(
                    R.string.action_item_edit to onEdit,
                    R.string.action_item_share to onShare,
                    R.string.action_item_calendar to onAddToCalendar,
                    R.string.action_item_delete to onDelete
                ).forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(text = stringResource(label)) },
                        onClick = {
                            menuOpen = false
                            action()
                        }
                    )
                }
            }
        }
    }
}
