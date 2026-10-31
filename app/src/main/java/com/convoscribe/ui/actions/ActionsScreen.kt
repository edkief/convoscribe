package com.convoscribe.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.ActionItemEntity
import com.convoscribe.data.OpenActionItem
import com.convoscribe.ui.components.ActionItemDialog
import com.convoscribe.ui.components.ActionItemRow
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.home.formatSessionDate
import com.convoscribe.ui.session.ActionItemActions
import com.convoscribe.ui.theme.AppTheme

/** *Open actions* (issue #31), from Home's menu. */
@Composable
fun ActionsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: ActionsViewModel = viewModel(
        factory = viewModelFactory { initializer { ActionsViewModel(container.sessionRepository) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    ActionsBody(
        state = state,
        onBack = { navController.popBackStack() },
        onGrouping = vm::setGrouping,
        actions = ActionItemActions(
            onDone = vm::setDone,
            onEdit = vm::edit,
            onDelete = vm::delete
        ),
        onShare = { open -> ActionItemIntents.share(context, open.item, open.sessionTitle, open.sessionCreatedAt) },
        onAddToCalendar = { open ->
            ActionItemIntents.addToCalendar(context, open.item, open.sessionTitle, open.sessionCreatedAt)
        },
        // The Summary tab when the item has no moment to go to.
        onOpen = { open ->
            val session = "session/${open.item.sessionId}"
            val at = open.item.atMs
            navController.navigate(if (at != null) "$session?tab=0&at=$at" else "$session?tab=1")
        }
    )
}

/**
 * The open action items under a heading per session or per owner,
 * with a choice between the two. Each row ticks off, edits, shares or
 * deletes the item as on the Summary tab; tapping it opens its session
 * at the moment it was agreed. By owner, each row also names its
 * session.
 */
@Composable
internal fun ActionsBody(
    state: ActionsUiState,
    onBack: () -> Unit,
    onGrouping: (ActionsGrouping) -> Unit,
    actions: ActionItemActions,
    onShare: (OpenActionItem) -> Unit,
    onAddToCalendar: (OpenActionItem) -> Unit,
    onOpen: (OpenActionItem) -> Unit,
    initialEditing: ActionItemEntity? = null
) {
    val spacing = AppTheme.spacing
    var editing by remember { mutableStateOf(initialEditing) }
    var deleting by remember { mutableStateOf<ActionItemEntity?>(null) }
    ScreenScaffold(title = stringResource(R.string.open_actions_title), onBack = onBack) { innerPadding ->
        if (state.loaded && state.groups.isEmpty()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_flag),
                title = stringResource(R.string.open_actions_empty),
                body = stringResource(R.string.open_actions_empty_hint),
                modifier = Modifier.padding(innerPadding)
            )
            return@ScreenScaffold
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AppTheme.sizes.contentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.l),
                verticalArrangement = Arrangement.spacedBy(spacing.m)
            ) {
                ChoiceGroup(
                    options = listOf(
                        ActionsGrouping.SESSION to stringResource(R.string.open_actions_by_session),
                        ActionsGrouping.OWNER to stringResource(R.string.open_actions_by_owner)
                    ),
                    selected = state.grouping,
                    onSelect = onGrouping
                )
                state.groups.forEach { group ->
                    Column(modifier = Modifier.padding(top = spacing.s)) {
                        Text(
                            text = group.title ?: stringResource(R.string.open_actions_no_owner),
                            style = MaterialTheme.typography.titleMedium
                        )
                        group.createdAt?.let {
                            Text(
                                text = formatSessionDate(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = spacing.s)) {
                            group.items.forEach { open ->
                                ActionItemRow(
                                    item = open.item,
                                    onCheckedChange = { actions.onDone(open.item.id, it) },
                                    onEdit = { editing = open.item },
                                    onShare = { onShare(open) },
                                    onAddToCalendar = { onAddToCalendar(open) },
                                    onDelete = { deleting = open.item },
                                    onOpen = { onOpen(open) },
                                    context = open.sessionTitle.takeIf { state.grouping == ActionsGrouping.OWNER }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { item ->
        ActionItemDialog(
            item = item,
            onConfirm = { text, owner, due ->
                actions.onEdit(item.id, text, owner, due)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.action_item_delete_title),
            message = stringResource(R.string.action_item_delete_message),
            confirmLabel = stringResource(R.string.action_item_delete),
            dismissLabel = stringResource(R.string.session_rename_cancel),
            onConfirm = {
                actions.onDelete(item.id)
                deleting = null
            },
            onDismiss = { deleting = null },
            destructive = true
        )
    }
}
