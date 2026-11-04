package com.convoscribe.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.pluralStringResource
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

/**
 * A field and an Add button that adds a talking point (issue #102),
 * then clears itself. With [splitLines], text pasted over several lines
 * goes to [onAdd] at once, for it to split (issue #114); otherwise its
 * lines are joined.
 */
@Composable
fun AddTalkingPointField(onAdd: (String) -> Unit, modifier: Modifier = Modifier, splitLines: Boolean = false) {
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
            onValueChange = {
                if (splitLines && TalkingPoints.isMultiLine(it)) {
                    onAdd(it)
                    text = ""
                } else {
                    text = it.replace('\n', ' ').take(TalkingPoints.MAX_LEN)
                }
            },
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

/** Editing a talking point's text (issue #102); Save stays off while it is blank. */
@Composable
fun TalkingPointDialog(initialText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.talking_point_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace('\n', ' ').take(TalkingPoints.MAX_LEN) },
                label = { Text(text = stringResource(R.string.talking_point_text_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.session_rename_cancel)) }
        }
    )
}

/**
 * *Paste talking points* (issue #114): a box for a block of text, one
 * point per line. The button says how many points [countFor] finds to
 * add and is off at none; [onImport] gets the text. The text survives
 * rotation.
 *
 * @param initialText text in the box on first composition (screenshots).
 */
@Composable
fun PastePointsDialog(
    countFor: (String) -> Int,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
    initialText: String = ""
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    val count = countFor(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.talking_points_paste_title)) },
        text = {
            // Scrolls so the field and buttons stay reachable at large font sizes.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
            ) {
                SupportingText(text = stringResource(R.string.talking_points_paste_help))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(text = stringResource(R.string.talking_points_paste_label)) },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = count > 0, onClick = { onImport(text) }) {
                Text(text = pluralStringResource(R.plurals.talking_points_paste_add, count, count))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.session_rename_cancel)) }
        }
    )
}
