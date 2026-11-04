package com.convoscribe.ui.organise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.TalkingPointList
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme

/** Home's Talking points section (issue #108): the talking-point lists (issue #102). */
@Composable
fun TalkingPointListsScreen(navController: NavHostController, onBack: (() -> Unit)? = null) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val vm: TalkingPointListsViewModel = viewModel(
        factory = viewModelFactory { initializer { TalkingPointListsViewModel(container.talkingPointLists) } }
    )
    val lists by vm.lists.collectAsStateWithLifecycle()
    TalkingPointListsBody(
        lists = lists,
        onBack = onBack,
        onSave = vm::save,
        onDelete = vm::delete
    )
}

/**
 * The lists with New list, or the editor for one: its name and its
 * points, one per line. Delete asks first. The draft survives rotation.
 *
 * @param initialDraft editor to show on first composition (screenshots).
 */
@Composable
internal fun TalkingPointListsBody(
    lists: List<TalkingPointList>,
    onBack: (() -> Unit)?,
    onSave: (TalkingPointListDraft) -> Unit,
    onDelete: (Long) -> Unit,
    initialDraft: TalkingPointListDraft? = null
) {
    var draftId by rememberSaveable { mutableStateOf(initialDraft?.id) }
    var draftName by rememberSaveable { mutableStateOf(initialDraft?.name.orEmpty()) }
    var draftPoints by rememberSaveable { mutableStateOf(initialDraft?.points.orEmpty()) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = { draft: TalkingPointListDraft? ->
        draftId = draft?.id
        draftName = draft?.name.orEmpty()
        draftPoints = draft?.points.orEmpty()
    }
    val editingId = draftId
    if (editingId != null) {
        val draft = TalkingPointListDraft(editingId, draftName, draftPoints)
        BackHandler { open(null) }
        TalkingPointListEditor(
            draft = draft,
            onChange = {
                draftName = it.name
                draftPoints = it.points
            },
            onSave = {
                onSave(draft)
                open(null)
            },
            onCancel = { open(null) }
        )
    } else {
        TalkingPointLists(
            lists = lists,
            onBack = onBack,
            onNew = { open(TalkingPointListDraft()) },
            onEdit = { open(TalkingPointListDraft.of(it)) },
            onDelete = { deletingId = it.id }
        )
    }
    lists.firstOrNull { it.id == deletingId }?.let { list ->
        ConfirmDialog(
            title = stringResource(R.string.talking_points_delete_title, list.name),
            message = stringResource(R.string.talking_points_delete_message),
            confirmLabel = stringResource(R.string.tags_delete),
            dismissLabel = stringResource(R.string.conversation_cancel),
            onConfirm = {
                onDelete(list.id)
                deletingId = null
            },
            onDismiss = { deletingId = null },
            destructive = true
        )
    }
}

@Composable
private fun TalkingPointLists(
    lists: List<TalkingPointList>,
    onBack: (() -> Unit)?,
    onNew: () -> Unit,
    onEdit: (TalkingPointList) -> Unit,
    onDelete: (TalkingPointList) -> Unit
) {
    val spacing = AppTheme.spacing
    ScreenScaffold(title = stringResource(R.string.talking_points_title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "intro") {
                Column(
                    modifier = Modifier
                        .widthIn(max = AppTheme.sizes.contentMaxWidth)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.s)
                ) {
                    SupportingText(text = stringResource(R.string.talking_points_explanation))
                    Button(onClick = onNew) { Text(text = stringResource(R.string.talking_points_new)) }
                    if (lists.isEmpty()) SupportingText(text = stringResource(R.string.talking_points_empty))
                }
            }
            items(lists, key = { it.id }) { list ->
                TalkingPointListRow(list = list, onEdit = { onEdit(list) }, onDelete = { onDelete(list) })
            }
        }
    }
}

/** Name, how many points, the first few, with Edit and Delete. */
@Composable
private fun TalkingPointListRow(list: TalkingPointList, onEdit: () -> Unit, onDelete: () -> Unit) {
    val spacing = AppTheme.spacing
    AppCard(modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = spacing.l, end = spacing.l, top = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(text = list.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(R.plurals.talking_points_count, list.points.size, list.points.size),
                style = MaterialTheme.typography.bodyMedium
            )
            if (list.points.isNotEmpty()) {
                Text(
                    text = list.points.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onEdit) { Text(text = stringResource(R.string.presets_edit)) }
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
private fun TalkingPointListEditor(
    draft: TalkingPointListDraft,
    onChange: (TalkingPointListDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val spacing = AppTheme.spacing
    ScreenScaffold(
        title = stringResource(
            if (draft.id == 0L) R.string.talking_points_new_title else R.string.talking_points_edit_title
        ),
        onBack = onCancel,
        actions = {
            TextButton(onClick = onSave, enabled = draft.canSave) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionCard(
                title = stringResource(R.string.talking_points_title),
                modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it.take(NAME_MAX_LEN))) },
                    label = { Text(text = stringResource(R.string.talking_points_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.points,
                    onValueChange = { onChange(draft.copy(points = it)) },
                    label = { Text(text = stringResource(R.string.talking_points_points_label)) },
                    minLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                )
            }
        }
    }
}

private const val NAME_MAX_LEN = 60
