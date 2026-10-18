package com.convoscribe.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog

/**
 * Landing screen. Today this is the empty-list shell; the only
 * action is the "New recording" [ExtendedFloatingActionButton] that
 * navigates to the `record` route — but only if the required model
 * bundles for the configured language are already on disk. When
 * any required bundle is missing the click handler routes to
 * `setup` instead, so the FAB works in both first-run and steady
 * state.
 *
 * The top-bar actions:
 *  - settings icon (`Icons.Filled.Settings`) — goes to the
 *    `settings` placeholder route (TASK-21 will fill it in);
 *  - overflow menu (`Icons.Filled.MoreVert`) — currently just the
 *    `Engine check` entry.
 *
 * The body is either an empty-state text or a `LazyColumn` of
 * `SessionRow`s. A normal click on a row navigates to
 * `session/{id}`; a long-press opens a confirmation dialog that,
 * on confirm, calls [HomeViewModel.delete].
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.home_settings)
                        )
                    }
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null
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
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecording,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.record_start)
                    )
                },
                text = { Text(text = stringResource(R.string.home_new_recording)) }
            )
        }
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.home_empty_with_hint),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(items = sessions, key = { it.id }) { row ->
                    val interactionSource = remember { MutableInteractionSource() }
                    ListItem(
                        headlineContent = { Text(text = row.title) },
                        supportingContent = { Text(text = row.subtitle) },
                        modifier = Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = ripple(),
                            onClick = { onOpenSession(row.id) },
                            onLongClick = { pendingDeleteId = row.id }
                        )
                    )
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(text = stringResource(R.string.home_delete_title)) },
            text = { Text(text = stringResource(R.string.home_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    pendingDeleteId = null
                }) {
                    Text(text = stringResource(R.string.home_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(text = stringResource(R.string.home_delete_cancel))
                }
            }
        )
    }
}
