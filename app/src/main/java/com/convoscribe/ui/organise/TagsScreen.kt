package com.convoscribe.ui.organise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.TagNames
import com.convoscribe.data.TagUsage
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme

/** Settings › Tags (issue #32), the `tags` route. */
@Composable
fun TagsScreen(navController: NavHostController) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val vm: TagsViewModel = viewModel(
        factory = viewModelFactory { initializer { TagsViewModel(container.sessionRepository) } }
    )
    val tags by vm.tags.collectAsStateWithLifecycle()
    TagsBody(
        tags = tags,
        onBack = { navController.popBackStack() },
        onRename = vm::rename,
        onDelete = vm::delete
    )
}

/**
 * Every tag with its session count, Rename and Delete. Rename opens a
 * name dialog; Delete asks first and leaves the sessions themselves.
 *
 * @param initialRename tag whose rename dialog shows on first composition (screenshots).
 */
@Composable
internal fun TagsBody(
    tags: List<TagUsage>,
    onBack: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    initialRename: TagUsage? = null
) {
    val spacing = AppTheme.spacing
    var renaming by remember { mutableStateOf(initialRename) }
    var deleting by remember { mutableStateOf<TagUsage?>(null) }
    ScreenScaffold(title = stringResource(R.string.tags_screen_title), onBack = onBack) { innerPadding ->
        if (tags.isEmpty()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_conversation),
                title = stringResource(R.string.tags_screen_empty_title),
                body = stringResource(R.string.tags_screen_empty_body),
                modifier = Modifier.padding(innerPadding)
            )
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "intro") {
                SupportingText(
                    text = stringResource(R.string.tags_screen_explanation),
                    modifier = Modifier
                        .widthIn(max = AppTheme.sizes.contentMaxWidth)
                        .fillMaxWidth()
                )
            }
            items(tags, key = { it.id }) { tag ->
                TagRow(tag = tag, onRename = { renaming = tag }, onDelete = { deleting = tag })
            }
        }
    }

    renaming?.let { tag ->
        RenameTagDialog(
            name = tag.name,
            onSave = {
                onRename(tag.id, it)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.tags_delete_title, tag.name),
            message = pluralStringResource(R.plurals.tags_delete_message, tag.sessionCount, tag.sessionCount),
            confirmLabel = stringResource(R.string.tags_delete),
            dismissLabel = stringResource(R.string.conversation_cancel),
            onConfirm = {
                onDelete(tag.id)
                deleting = null
            },
            onDismiss = { deleting = null },
            destructive = true
        )
    }
}

@Composable
private fun TagRow(tag: TagUsage, onRename: () -> Unit, onDelete: () -> Unit) {
    val spacing = AppTheme.spacing
    AppCard(modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = spacing.l, end = spacing.l, top = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(text = tag.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(R.plurals.tags_session_count, tag.sessionCount, tag.sessionCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onRename) { Text(text = stringResource(R.string.tags_rename)) }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(text = stringResource(R.string.tags_delete))
            }
        }
    }
}

@Composable
private fun RenameTagDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(name) { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.tags_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(TagNames.MAX_LEN) },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.session_rename_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                SupportingText(text = stringResource(R.string.tags_rename_help))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = TagNames.normalise(value) != null) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.session_rename_cancel)) }
        }
    )
}
