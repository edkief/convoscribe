package com.convoscribe.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.StatusBadge
import com.convoscribe.ui.components.statusLabel
import com.convoscribe.ui.theme.AppTheme

/**
 * Landing screen: the session list and the "New recording" action,
 * which goes to the `record` route — or to `setup` when a model bundle
 * the configured language and engine need is missing, so it works both
 * on first run and in steady state.
 *
 * The top bar has Settings and an overflow menu with Engine check. Each
 * session is a card (title, date, duration, speakers, language and a
 * status badge); tapping it opens `session/{id}`, and Delete is in the
 * card's overflow menu (long-press still works) behind a confirmation.
 *
 * @param navController app-level controller; passed in (rather than
 *   re-read via `rememberNavController`) so a configuration change
 *   pushes back onto the same back stack the parent owns.
 */
@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(repository = container.sessionRepository)
            }
        }
    )
    // Compute the click handler in composition so the language
    // setting is read fresh every recomposition. The container is
    // cheap to access — it is held on the application instance.
    val onNewRecording: () -> Unit = {
        val settings = container.settingsRepository.settings.value
        val missing = container.modelStore.missing(
            ModelCatalog.requiredFor(settings.asrLanguage, settings.asrEngine)
        )
        val target = if (missing.isEmpty()) "record" else "setup"
        navController.navigate(target)
    }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    HomeBody(
        sessions = sessions,
        onNewRecording = onNewRecording,
        onOpenSession = { id -> navController.navigate("session/$id") },
        onDelete = vm::delete,
        onSettings = { navController.navigate("settings") },
        onEngineCheck = { navController.navigate("engine-check") }
    )
}

/**
 * Render-only body of [HomeScreen], so previews and screenshot tests
 * can draw it with fake [sessions]. The delete confirmation dialog
 * lives here because it is pure UI state.
 */
@Composable
internal fun HomeBody(
    sessions: List<SessionRow>,
    onNewRecording: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
    onEngineCheck: () -> Unit
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.home_settings)
                )
            }
            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.home_more_options)
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.home_engine_check)) },
                        onClick = {
                            overflowExpanded = false
                            onEngineCheck()
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            // Hidden while the list is empty: the empty state carries the same action.
            if (sessions.isNotEmpty()) NewRecordingButton(onClick = onNewRecording)
        }
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_conversation),
                title = stringResource(R.string.home_empty_title),
                body = stringResource(R.string.home_empty_body),
                actionLabel = stringResource(R.string.home_new_recording),
                onAction = onNewRecording,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            val spacing = AppTheme.spacing
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.s,
                    // Keep the last card clear of the floating button.
                    bottom = AppTheme.sizes.recordButton + spacing.xl
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.m),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(items = sessions, key = { it.id }) { row ->
                    SessionCard(
                        row = row,
                        onOpen = { onOpenSession(row.id) },
                        onRequestDelete = { pendingDeleteId = row.id },
                        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
                    )
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.home_delete_title),
            message = stringResource(R.string.home_delete_message),
            confirmLabel = stringResource(R.string.home_delete_confirm),
            dismissLabel = stringResource(R.string.home_delete_cancel),
            destructive = true,
            onConfirm = {
                onDelete(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }
}

/** The primary action. Flat (no shadow) on e-ink, where it is solid black. */
@Composable
private fun NewRecordingButton(onClick: () -> Unit) {
    val elevation = if (AppTheme.isEink) {
        val flat = AppTheme.sizes.elevation
        FloatingActionButtonDefaults.elevation(flat, flat, flat, flat)
    } else {
        FloatingActionButtonDefaults.elevation()
    }
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = {
            // The text says it; the icon is decoration.
            Icon(painter = painterResource(R.drawable.ic_mic), contentDescription = null)
        },
        text = { Text(text = stringResource(R.string.home_new_recording)) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = elevation
    )
}

/**
 * One session: title and overflow menu on the first line, then
 * `date · duration · N speakers · FR`, and a status badge while it is
 * recording, identifying speakers or failed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    row: SessionRow,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val spacing = AppTheme.spacing
    val meta = listOfNotNull(
        formatSessionDate(row.createdAt),
        formatDuration(row.durationMs).ifEmpty { null },
        if (row.speakerCount > 0) {
            pluralStringResource(R.plurals.home_speaker_count, row.speakerCount, row.speakerCount)
        } else {
            null
        },
        languageTag(row.language)
    ).joinToString(" · ")
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onOpen, onLongClick = onRequestDelete)
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.l, top = spacing.xs, bottom = spacing.l),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = spacing.m),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (statusLabel(row.status) != null) {
                    StatusBadge(status = row.status, modifier = Modifier.padding(top = spacing.xs))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.home_session_more, row.title)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.home_delete_confirm)) },
                        leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRequestDelete()
                        }
                    )
                }
            }
        }
    }
}
