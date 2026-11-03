package com.convoscribe.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import com.convoscribe.R
import com.convoscribe.data.TalkingPoints
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.ui.theme.AppTheme

/** What a talking point's menu offers (issue #102); a `null` action is left out. */
class TalkingPointMenu(
    val onEdit: (() -> Unit)? = null,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null
)

/**
 * One talking point (issue #102): a checkbox, the text and, once
 * covered, when (tap to play from there with [onOpenTime]), and a
 * [menu]. Covered or not is said in words as well as by the box, never
 * by colour alone.
 */
@Composable
fun TalkingPointRow(
    text: String,
    covered: Boolean,
    coveredAtMs: Long?,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onOpenTime: (() -> Unit)? = null,
    menu: TalkingPointMenu? = null
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val state = stringResource(if (covered) R.string.talking_point_covered else R.string.talking_point_open)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = covered,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { stateDescription = state }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (covered) scheme.onSurfaceVariant else scheme.onSurface
            )
            if (covered) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    Text(text = state, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (coveredAtMs != null) {
                        val time = TranscriptFormatter.timestamp(coveredAtMs)
                        Text(
                            text = time,
                            style = AppTheme.typography.figures,
                            color = if (onOpenTime != null) scheme.primary else scheme.onSurfaceVariant,
                            modifier = if (onOpenTime != null) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.action_item_open_source, time),
                                    onClick = onOpenTime
                                )
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
        if (menu != null) TalkingPointMenuButton(menu)
    }
}

@Composable
private fun TalkingPointMenuButton(menu: TalkingPointMenu) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_item_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOfNotNull(
                menu.onEdit?.let { R.string.talking_point_edit to it },
                menu.onMoveUp?.let { R.string.talking_point_move_up to it },
                menu.onMoveDown?.let { R.string.talking_point_move_down to it },
                menu.onDelete?.let { R.string.tags_delete to it }
            ).forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(label)) },
                    onClick = {
                        open = false
                        action()
                    }
                )
            }
        }
    }
}

/** A field and an Add button that adds a talking point (issue #102), then clears itself. */
@Composable
fun AddTalkingPointField(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.replace('\n', ' ').take(TalkingPoints.MAX_LEN) },
            label = { Text(text = stringResource(R.string.talking_point_add_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = add, enabled = text.isNotBlank()) {
            Text(text = stringResource(R.string.talking_point_add))
        }
    }
}
