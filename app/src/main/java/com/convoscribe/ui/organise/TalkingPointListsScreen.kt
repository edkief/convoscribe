package com.convoscribe.ui.organise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.TalkingPointList
import com.convoscribe.data.UncoveredPoint
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.home.formatSessionDate
import com.convoscribe.ui.home.newRecordingRoute
import com.convoscribe.ui.sessionRoute
import com.convoscribe.ui.theme.AppTheme

/**
 * Home's Talking points section (issue #108): the talking-point lists
 * (issue #102), each with *Record with this list*, and the points
 * recent conversations did not cover.
 */
@Composable
fun TalkingPointListsScreen(navController: NavHostController, onBack: (() -> Unit)? = null) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val vm: TalkingPointListsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TalkingPointListsViewModel(
                    store = container.talkingPointLists,
                    uncovered = container.sessionRepository.observeRecentUncovered(
                        sinceMs = System.currentTimeMillis() - NOT_COVERED_WINDOW_MS,
                        limit = NOT_COVERED_LIMIT
                    )
                )
            }
        }
    )
    val lists by vm.lists.collectAsStateWithLifecycle()
    val notCovered by vm.notCovered.collectAsStateWithLifecycle()
    TalkingPointListsBody(
        lists = lists,
        notCovered = notCovered,
        onBack = onBack,
        onSave = vm::save,
        onDelete = vm::delete,
        onRecord = { list -> navController.navigate(newRecordingRoute(container, attachListId = list.id)) },
        onOpenSession = { id -> navController.navigate(sessionRoute(id, tab = SUMMARY_TAB)) }
    )
}

/**
 * The lists with New list and *Not covered last time*, or the editor
 * for one: its name and its points, one row each (issue #111), and
 * Delete for a saved list. Delete asks first. The draft survives
 * rotation.
 *
 * @param initialDraft editor to show on first composition (screenshots).
 */
@Composable
internal fun TalkingPointListsBody(
    lists: List<TalkingPointList>,
    onBack: (() -> Unit)?,
    onSave: (TalkingPointListDraft) -> Unit,
    onDelete: (Long) -> Unit,
    notCovered: List<UncoveredPoint> = emptyList(),
    onRecord: (TalkingPointList) -> Unit = {},
    onOpenSession: (Long) -> Unit = {},
    initialDraft: TalkingPointListDraft? = null
) {
    var draftId by rememberSaveable { mutableStateOf(initialDraft?.id) }
    var draftName by rememberSaveable { mutableStateOf(initialDraft?.name.orEmpty()) }
    var draftPoints by rememberSaveable(stateSaver = PointsSaver) { mutableStateOf(initialDraft?.points ?: listOf("")) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = { draft: TalkingPointListDraft? ->
        draftId = draft?.id
        draftName = draft?.name.orEmpty()
        draftPoints = draft?.points ?: listOf("")
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
            onCancel = { open(null) },
            onDelete = { deletingId = editingId }
        )
    } else {
        TalkingPointLists(
            lists = lists,
            notCovered = notCovered,
            onBack = onBack,
            onNew = { open(TalkingPointListDraft()) },
            onEdit = { open(TalkingPointListDraft.of(it)) },
            onRecord = onRecord,
            onOpenSession = onOpenSession
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
                open(null)
            },
            onDismiss = { deletingId = null },
            destructive = true
        )
    }
}

@Composable
private fun TalkingPointLists(
    lists: List<TalkingPointList>,
    notCovered: List<UncoveredPoint>,
    onBack: (() -> Unit)?,
    onNew: () -> Unit,
    onEdit: (TalkingPointList) -> Unit,
    onRecord: (TalkingPointList) -> Unit,
    onOpenSession: (Long) -> Unit
) {
    val spacing = AppTheme.spacing
    ScreenScaffold(
        title = stringResource(R.string.talking_points_title),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(imageVector = Icons.Filled.Add, contentDescription = null) },
                text = { Text(text = stringResource(R.string.talking_points_new)) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.s,
                // Keep the last row clear of the floating button.
                bottom = AppTheme.sizes.recordButton + spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lists.isEmpty()) {
                item(key = "intro") {
                    Column(
                        modifier = Modifier
                            .widthIn(max = AppTheme.sizes.contentMaxWidth)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(spacing.s)
                    ) {
                        SupportingText(text = stringResource(R.string.talking_points_explanation))
                        SupportingText(text = stringResource(R.string.talking_points_empty))
                    }
                }
            }
            items(lists, key = { it.id }) { list ->
                TalkingPointListRow(list = list, onRecord = { onRecord(list) }, onEdit = { onEdit(list) })
            }
            if (notCovered.isNotEmpty()) {
                item(key = "not-covered") {
                    Text(
                        text = stringResource(R.string.talking_points_not_covered_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .widthIn(max = AppTheme.sizes.contentMaxWidth)
                            .fillMaxWidth()
                            .padding(top = spacing.m)
                    )
                }
                items(notCovered, key = { "point:${it.pointId}" }) { point ->
                    NotCoveredRow(point = point, onClick = { onOpenSession(point.sessionId) })
                }
            }
        }
    }
}

/** Name, how many points, the first few, with *Record with this list* and Edit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TalkingPointListRow(list: TalkingPointList, onRecord: () -> Unit, onEdit: () -> Unit) {
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
        // Wraps so Edit moves under Record with this list at large font sizes.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.m, vertical = spacing.s),
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            FilledTonalButton(onClick = onRecord) {
                Icon(painter = painterResource(R.drawable.ic_mic), contentDescription = null)
                Text(
                    text = stringResource(R.string.talking_points_record_with),
                    modifier = Modifier.padding(start = spacing.s)
                )
            }
            TextButton(onClick = onEdit) { Text(text = stringResource(R.string.presets_edit)) }
        }
    }
}

/** A point left open: its text, and the conversation and day it belongs to. */
@Composable
private fun NotCoveredRow(point: UncoveredPoint, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        modifier = Modifier
            .widthIn(max = AppTheme.sizes.contentMaxWidth)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Text(text = point.text, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(
                R.string.talking_points_not_covered_from,
                point.sessionTitle,
                formatSessionDate(point.sessionCreatedAt)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** How far back *Not covered last time* looks (issue #108). */
private const val NOT_COVERED_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

/** At most this many points under *Not covered last time*. */
private const val NOT_COVERED_LIMIT = 10

/** The session screen's Summary tab, which heads with its talking points. */
private const val SUMMARY_TAB = 1

@Composable
private fun TalkingPointListEditor(
    draft: TalkingPointListDraft,
    onChange: (TalkingPointListDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val spacing = AppTheme.spacing
    // The row to focus once composed: one just added with Next or Add point.
    var focusIndex by remember { mutableStateOf<Int?>(null) }
    ScreenScaffold(
        title = stringResource(
            if (draft.id == 0L) R.string.talking_points_new_title else R.string.talking_points_edit_title
        ),
        onBack = onCancel,
        actions = {
            if (draft.id != 0L) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = stringResource(R.string.tags_delete))
                }
            }
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
                Text(
                    text = stringResource(R.string.talking_points_points_heading),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
                draft.points.forEachIndexed { index, point ->
                    TalkingPointEditRow(
                        index = index,
                        count = draft.points.size,
                        text = point,
                        focus = focusIndex == index,
                        onFocused = { focusIndex = null },
                        onTextChange = { onChange(draft.withPoint(index, it)) },
                        onNext = {
                            onChange(draft.addPoint(after = index))
                            focusIndex = index + 1
                        },
                        onMove = { by -> onChange(draft.movePoint(index, by)) },
                        onRemove = { onChange(draft.removePoint(index)) }
                    )
                }
                TextButton(
                    onClick = {
                        onChange(draft.addPoint())
                        focusIndex = draft.points.size
                    }
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.talking_points_add_point),
                        modifier = Modifier.padding(start = spacing.s)
                    )
                }
            }
        }
    }
}

private const val NAME_MAX_LEN = 60

/** Saves a draft's points across rotation and recreation. */
private val PointsSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

/** At or above this font scale a point's buttons go under its field. */
private const val STACKED_FONT_SCALE = 1.5f

/**
 * One point in the list editor (issue #111): its field, Move up, Move
 * down and Remove. Each button names the point for TalkBack, or its
 * number while it is blank. Next adds a point after this one.
 */
@Composable
private fun TalkingPointEditRow(
    index: Int,
    count: Int,
    text: String,
    focus: Boolean,
    onFocused: () -> Unit,
    onTextChange: (String) -> Unit,
    onNext: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    val number = stringResource(R.string.talking_points_point_label, index + 1)
    val name = text.trim().ifEmpty { number }
    val field = @Composable { modifier: Modifier ->
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(text = number) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = modifier.focusRequester(requester)
        )
    }
    val buttons = @Composable {
        IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.talking_point_move_up_named, name)
            )
        }
        IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.talking_point_move_down_named, name)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.record_talking_point_remove, name)
            )
        }
    }
    if (LocalDensity.current.fontScale >= STACKED_FONT_SCALE) {
        Column(modifier = Modifier.fillMaxWidth()) {
            field(Modifier.fillMaxWidth())
            Row(modifier = Modifier.align(Alignment.End)) { buttons() }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            field(Modifier.weight(1f))
            buttons()
        }
    }
}
