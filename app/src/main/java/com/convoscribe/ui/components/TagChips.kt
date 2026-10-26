package com.convoscribe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.convoscribe.R
import com.convoscribe.data.TagNames
import com.convoscribe.ui.theme.AppTheme

/**
 * A session's tags (issue #32) as small outlined labels that wrap onto
 * more lines. Read-only; draws nothing for no tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
    ) {
        tags.forEach { TagLabel(name = it) }
    }
}

@Composable
private fun TagLabel(name: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (AppTheme.isEink) scheme.surface else scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        border = if (AppTheme.isEink) BorderStroke(AppTheme.sizes.outline, scheme.outline) else null
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s, vertical = AppTheme.spacing.xxs)
        )
    }
}

/** "None", or the tags joined by commas, for a [PickerField]. */
@Composable
fun tagsSummary(tags: List<String>): String =
    if (tags.isEmpty()) stringResource(R.string.tags_none) else tags.joinToString(", ")

/**
 * Pick a session's tags (issue #32): tick existing ones from
 * [allTags], or type a new name and add it. Names are matched
 * ignoring case, so typing an existing tag ticks it. [onConfirm] gets
 * the ticked names in the order shown.
 */
@Composable
fun TagPickerDialog(
    allTags: List<String>,
    selected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val names =
        remember(
            allTags,
            selected
        ) { mutableStateListOf<String>().apply { addAll(TagNames.clean(allTags + selected)) } }
    val ticked = remember(selected) { mutableStateListOf<String>().apply { addAll(TagNames.clean(selected)) } }
    var typed by rememberSaveable { mutableStateOf("") }
    val add: () -> Unit = {
        TagNames.normalise(typed)?.let { name ->
            val key = TagNames.key(name)
            val existing = names.firstOrNull { TagNames.key(it) == key }
            if (existing == null) names.add(name)
            val shown = existing ?: name
            if (ticked.none { TagNames.key(it) == key }) ticked.add(shown)
        }
        typed = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.tags_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(TagNames.MAX_LEN) },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.tags_new_label)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = {
                        IconButton(onClick = add, enabled = TagNames.normalise(typed) != null) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.tags_add))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (names.isEmpty()) {
                    SupportingText(
                        text = stringResource(R.string.tags_empty_help),
                        modifier = Modifier.padding(top = AppTheme.spacing.s)
                    )
                }
                names.forEach { name ->
                    val checked = name in ticked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppTheme.sizes.touchTarget)
                            .toggleable(value = checked, role = Role.Checkbox) {
                                if (checked) ticked.remove(name) else ticked.add(name)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(text = name, modifier = Modifier.padding(start = AppTheme.spacing.s))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // A name typed but not added yet counts too.
                    add()
                    onConfirm(names.filter { it in ticked })
                }
            ) {
                Text(text = stringResource(R.string.tags_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.conversation_cancel))
            }
        }
    )
}
