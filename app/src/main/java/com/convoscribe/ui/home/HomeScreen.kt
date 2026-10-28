package com.convoscribe.ui.home

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.audio.AudioImportException
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.ImportState
import com.convoscribe.recording.PendingImport
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.service.RecordingService
import com.convoscribe.service.importStepText
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.StatusBadge
import com.convoscribe.ui.components.TagChipRow
import com.convoscribe.ui.components.statusLabel
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.ui.update.UpdateActions
import com.convoscribe.ui.update.UpdateBanner
import com.convoscribe.ui.update.hasBanner
import com.convoscribe.ui.update.rememberUpdateActions
import com.convoscribe.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Landing screen: the session list and the "New recording" action,
 * which goes to the `record` route — or to `setup` when a model bundle
 * the configured language and engine need is missing, so it works both
 * on first run and in steady state.
 *
 * Audio import (issue #3) starts here too: *Import audio…* picks a
 * file, and a file shared or opened with ConvoScribe arrives through
 * `AppContainer.pendingImport`. Either way the screen refuses it while
 * a recording, re-transcription or import runs, sends the user to
 * setup first when the models are missing (and resumes on the way
 * back), then hands it to the foreground service. A banner shows the
 * import's progress with Cancel, a failure is a snackbar, and the new
 * session opens as soon as it exists.
 *
 * A newer release (issue #15) shows as a banner above the list, with
 * View notes, Update, Not now and Skip this version, then the
 * download's progress and Install.
 *
 * Once sessions are tagged (issue #32), a row of tag chips above the
 * list filters it to sessions with any of the ticked tags.
 *
 * The top bar has Settings and an overflow menu with Engine check. Each
 * session is a card (title, date, duration, speakers, language, a
 * status badge and its tags); tapping it opens `session/{id}`, and Delete is in the
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
    val tagFilter by vm.tagFilter.collectAsStateWithLifecycle()
    val importer = container.importAudioUseCase
    val importState by importer.state.collectAsStateWithLifecycle()
    val createdSessionId by importer.createdSessionId.collectAsStateWithLifecycle()
    val pendingImport by container.pendingImport.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val snackbarHostState = remember { SnackbarHostState() }
    // Messages outlive the effects below, which end as soon as they
    // clear the request or the failure they are about.
    val messageScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        messageScope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long) }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.pendingImport.value = PendingImport(uri)
    }

    // Only while this screen is in front: on the way to setup it is
    // not, so marking the request as sent there does not re-trigger it.
    LaunchedEffect(pendingImport, resumed) {
        val request = pendingImport ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        val busy = container.recordingController.state.value.phase != RecordingPhase.IDLE ||
            container.retranscribeUseCase.isRunning ||
            importer.isRunning
        if (busy) {
            container.pendingImport.value = null
            showMessage(context.getString(R.string.home_import_busy))
            return@LaunchedEffect
        }
        val settings = container.settingsRepository.settings.value
        val missing = container.modelStore.missing(
            ModelCatalog.requiredFor(settings.asrLanguage, settings.asrEngine)
        )
        if (missing.isNotEmpty()) {
            if (request.sentToSetup) {
                container.pendingImport.value = null
                showMessage(context.getString(R.string.home_import_models_missing))
            } else {
                container.pendingImport.value = request.copy(sentToSetup = true)
                navController.navigate(
                    "setup?engine=${settings.asrEngine}&language=${settings.asrLanguage}&back=true"
                )
            }
            return@LaunchedEffect
        }
        container.pendingImport.value = null
        ContextCompat.startForegroundService(context, RecordingService.importIntent(context, request.uri))
    }
    LaunchedEffect(createdSessionId, resumed) {
        val id = createdSessionId ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        importer.consumeCreated()
        navController.navigate("session/$id")
    }
    val updateState by container.updateManager.state.collectAsStateWithLifecycle()
    val updateDismissed by container.updateManager.bannerDismissed.collectAsStateWithLifecycle()
    val updateActions = rememberUpdateActions(container.updateManager, showMessage)
    LaunchedEffect(importState) {
        val failed = importState as? ImportState.Failed ?: return@LaunchedEffect
        importer.acknowledgeFailure()
        showMessage(context.getString(R.string.home_import_failed, importErrorText(context, failed)))
    }

    HomeBody(
        sessions = sessions,
        tagFilter = tagFilter,
        onToggleTag = vm::toggleTag,
        onClearTagFilter = vm::clearTagFilter,
        importing = importState as? ImportState.Running,
        update = if (updateDismissed) UpdateState.Idle else updateState,
        updateActions = updateActions,
        snackbarHostState = snackbarHostState,
        onImport = { pickAudio.launch(IMPORT_MIME_TYPES) },
        onCancelImport = { context.startService(RecordingService.cancelImportIntent(context)) },
        onNewRecording = onNewRecording,
        onOpenSession = { id -> navController.navigate("session/$id") },
        onDelete = vm::delete,
        onSettings = { navController.navigate("settings") },
        onEngineCheck = { navController.navigate("engine-check") },
        onSearch = { navController.navigate("search") },
        onAskAcross = { navController.navigate("ask") }
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
    tagFilter: TagFilter = TagFilter(),
    onToggleTag: (Long) -> Unit = {},
    onClearTagFilter: () -> Unit = {},
    importing: ImportState.Running? = null,
    update: UpdateState = UpdateState.Idle,
    updateActions: UpdateActions = UpdateActions(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onNewRecording: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
    onEngineCheck: () -> Unit,
    onSearch: () -> Unit = {},
    onAskAcross: () -> Unit = {}
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    // A filter that matches nothing still means there are sessions.
    val hasSessions = sessions.isNotEmpty() || tagFilter.active
    // Search is a field above the list where there is room, an icon otherwise.
    val searchable = hasSessions
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            // Ask across sessions (issue #49), once there is something to ask about.
            if (searchable) {
                IconButton(onClick = onAskAcross) {
                    Icon(
                        painter = painterResource(R.drawable.ic_conversation),
                        contentDescription = stringResource(R.string.ask_across_open)
                    )
                }
            }
            if (searchable && !wide) {
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.home_search)
                    )
                }
            }
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
                        text = { Text(text = stringResource(R.string.home_import_audio_menu)) },
                        onClick = {
                            overflowExpanded = false
                            onImport()
                        }
                    )
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
            if (hasSessions) NewRecordingButton(onClick = onNewRecording)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (update.hasBanner()) {
                UpdateBanner(
                    state = update,
                    actions = updateActions,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.screen)
                )
            }
            importing?.let { ImportBanner(state = it, onCancel = onCancelImport) }
            if (searchable && wide) SearchEntry(onClick = onSearch)
            if (tagFilter.tags.isNotEmpty()) {
                TagFilterRow(filter = tagFilter, onToggle = onToggleTag)
            }
            SessionList(
                sessions = sessions,
                filtered = tagFilter.active,
                onClearFilter = onClearTagFilter,
                onNewRecording = onNewRecording,
                onImport = onImport,
                onOpenSession = onOpenSession,
                onRequestDelete = { pendingDeleteId = it }
            )
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

/** Width from which Home shows a search field instead of a top-bar icon. */
private const val WIDE_SCREEN_DP = 600

/** A field-shaped button above the session list that opens search. */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen, vertical = spacing.s),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = AppTheme.sizes.contentMaxWidth),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s)
            ) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                Text(
                    text = stringResource(R.string.home_search_field),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One chip per tag (issue #32), scrolling sideways; ticking chips
 * narrows the list to sessions with any of them.
 */
@Composable
private fun TagFilterRow(filter: TagFilter, onToggle: (Long) -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(spacing.s)
    ) {
        filter.tags.forEach { tag ->
            val selected = tag.id in filter.selected
            FilterChip(
                selected = selected,
                onClick = { onToggle(tag.id) },
                label = { Text(text = tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (selected) {
                    { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                }
            )
        }
    }
}

/**
 * The session cards, or the empty state: with New recording and
 * Import audio, or, when the tag filter hides everything, with Clear
 * filter.
 */
@Composable
private fun SessionList(
    sessions: List<SessionRow>,
    filtered: Boolean,
    onClearFilter: () -> Unit,
    onNewRecording: () -> Unit,
    onImport: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit
) {
    if (sessions.isEmpty() && filtered) {
        EmptyState(
            icon = painterResource(R.drawable.ic_conversation),
            title = stringResource(R.string.home_filter_empty_title),
            body = stringResource(R.string.home_filter_empty_body),
            actionLabel = stringResource(R.string.home_filter_clear),
            onAction = onClearFilter
        )
    } else if (sessions.isEmpty()) {
        EmptyState(
            icon = painterResource(R.drawable.ic_conversation),
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            actionLabel = stringResource(R.string.home_new_recording),
            onAction = onNewRecording,
            secondaryActionLabel = stringResource(R.string.home_import_audio),
            onSecondaryAction = onImport
        )
    } else {
        val spacing = AppTheme.spacing
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
                    onRequestDelete = { onRequestDelete(row.id) },
                    modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
                )
            }
        }
    }
}

/**
 * "Importing <file>", the step with its percentage, a progress bar and
 * Cancel (until the session exists), above the session list.
 */
@Composable
private fun ImportBanner(state: ImportState.Running, onCancel: () -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen, vertical = spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.name ?: stringResource(R.string.notification_import_title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(importStepText(state.step), (state.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.cancellable) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.home_import_cancel))
                }
            }
        }
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** Mime types the import picker offers; see the manifest's intent filters. */
private val IMPORT_MIME_TYPES = arrayOf("audio/*", "application/ogg")

/** Why [failed] failed, as a sentence. */
private fun importErrorText(context: Context, failed: ImportState.Failed): String = when (failed.reason) {
    AudioImportException.Reason.UNREADABLE -> context.getString(R.string.import_error_unreadable)
    AudioImportException.Reason.NO_AUDIO_TRACK -> context.getString(R.string.import_error_no_audio_track)
    AudioImportException.Reason.UNSUPPORTED_FORMAT -> context.getString(R.string.import_error_unsupported)
    AudioImportException.Reason.EMPTY -> context.getString(R.string.import_error_empty)
    null -> failed.message.orEmpty()
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
                TagChipRow(tags = row.tags, modifier = Modifier.padding(top = spacing.xs))
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
