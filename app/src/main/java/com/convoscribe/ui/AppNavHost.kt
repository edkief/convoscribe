package com.convoscribe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.convoscribe.ConvoApp
import com.convoscribe.ui.enginecheck.EngineCheckScreen
import com.convoscribe.ui.home.HomeScreen
import com.convoscribe.ui.record.RecordScreen
import com.convoscribe.ui.search.SearchScreen
import com.convoscribe.ui.session.SessionScreen
import com.convoscribe.ui.settings.SettingsScreen
import com.convoscribe.ui.setup.ModelSetupScreen
import com.convoscribe.ui.speakers.SpeakersScreen
import com.convoscribe.ui.templates.SummaryTemplatesScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_SETUP = "setup"
private const val ROUTE_SETUP_WITH_ENGINE = "setup?engine={engine}&language={language}&back={back}"
private const val ROUTE_RECORD = "record"
private const val ROUTE_RECORD_RESUME = "record/resume/{id}"
private const val ROUTE_SESSION_DETAIL = "session/{id}?tab={tab}&line={line}&at={at}"
private const val ROUTE_ENGINE_CHECK = "engine-check"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SPEAKERS = "speakers"
private const val ROUTE_SUMMARY_TEMPLATES = "summary-templates"
private const val ROUTE_SEARCH = "search"

private const val NO_ARG = -1L

/**
 * Route to session [id], optionally opening [tab] at transcript line
 * [lineId] with the player cued to [atMs].
 */
internal fun sessionRoute(id: Long, tab: Int = 0, lineId: Long? = null, atMs: Long? = null): String = buildString {
    append("session/").append(id).append("?tab=").append(tab)
    if (lineId != null) append("&line=").append(lineId)
    if (atMs != null) append("&at=").append(atMs)
}

/**
 * Single navigation graph for the app. The first-run model download
 * lives behind `setup`; [startDestination] is computed by
 * `MainActivity` from whether the language's required bundles are
 * already installed, so a fully-installed app skips straight to
 * `home` and a fresh install lands on `setup` first.
 *
 * Routes:
 *  - `home` — landing screen with the "New recording" FAB and
 *    session list;
 *  - `setup` — first-run model download (also reachable with
 *    `?engine=npu|cpu` to scope the download to one engine, and
 *    `&language=en|fr` for another language than Settings'; with
 *    `&back=true` it pops back to the caller when done, which is how
 *    the Record screen gets a new conversation's models);
 *  - `record` — live-transcript screen reached from the FAB;
 *  - `record/resume/{id}` — the same screen, continuing session
 *    `id` (reached from the session screen's mic button); stopping
 *    returns to that session;
 *  - `engine-check` — diagnostics screen reached from the home
 *    overflow menu or the Settings screen;
 *  - `settings` — LLM endpoint, transcription engine, threads,
 *    speakers. Form saves on save/back; engine changes are
 *    written immediately by the segmented button.
 *  - `speakers` — enroll a known speaker (record a 15-second
 *    voice sample so the diarizer can label the cluster with
 *    the speaker's real name), list the enrolled names, delete
 *    them. Reachable from the Speakers section of Settings; the
 *    screen renders an "Open setup" hint when the embedding
 *    model is not installed.
 *  - `search` — search across sessions (issue #24), reached from
 *    the home top bar;
 *  - `session/{id}` — session-detail screen (transcript tab,
 *    summary tab and ask tab). The route id is a `Long` so the
 *    home list's `session/${row.id}` push lands on the right row.
 *    Search results (issue #24) add `?tab=` (the tab to open),
 *    `&line=` (the segment id to scroll to) and `&at=` (where to
 *    cue the player, in ms) through [sessionRoute].
 *
 * A request from the floating control or the Quick Settings tile
 * (`pendingOpenRecord`, issue #7) pushes `record`, unless a Record
 * screen is already in front.
 */
@Composable
fun AppNavHost(startDestination: String = ROUTE_HOME) {
    val navController = rememberNavController()
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val openRecord by container.pendingOpenRecord.collectAsStateWithLifecycle()
    LaunchedEffect(openRecord) {
        if (!openRecord) return@LaunchedEffect
        container.pendingOpenRecord.value = false
        val route = navController.currentDestination?.route
        if (route != null && !route.startsWith(ROUTE_RECORD) && !route.startsWith(ROUTE_SETUP)) {
            navController.navigate(ROUTE_RECORD) { launchSingleTop = true }
        }
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_HOME) {
            HomeScreen(navController = navController)
        }
        composable(ROUTE_SETUP) {
            ModelSetupScreen(
                onFinished = {
                    navController.navigate(ROUTE_HOME) {
                        popUpTo(ROUTE_SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = ROUTE_SETUP_WITH_ENGINE,
            arguments = listOf(
                navArgument("engine") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("language") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("back") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val back = args?.getBoolean("back") == true
            ModelSetupScreen(
                initialEngine = args?.getString("engine"),
                initialLanguage = args?.getString("language"),
                onFinished = {
                    if (back) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(ROUTE_HOME) {
                            popUpTo(ROUTE_SETUP) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(ROUTE_RECORD) {
            RecordScreen(navController = navController)
        }
        composable(
            route = ROUTE_RECORD_RESUME,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.LongType
                }
            )
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            RecordScreen(navController = navController, resumeSessionId = id.takeIf { it > 0L })
        }
        composable(ROUTE_ENGINE_CHECK) {
            EngineCheckScreen(navController = navController)
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(navController = navController)
        }
        composable(ROUTE_SPEAKERS) {
            SpeakersScreen(navController = navController)
        }
        composable(ROUTE_SUMMARY_TEMPLATES) {
            SummaryTemplatesScreen(navController = navController)
        }
        composable(ROUTE_SEARCH) {
            SearchScreen(navController = navController)
        }
        composable(
            route = ROUTE_SESSION_DETAIL,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.LongType
                },
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("line") {
                    type = NavType.LongType
                    defaultValue = NO_ARG
                },
                navArgument("at") {
                    type = NavType.LongType
                    defaultValue = NO_ARG
                }
            )
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val id = args?.getLong("id") ?: 0L
            SessionScreen(
                sessionId = id,
                navController = navController,
                initialTab = args?.getInt("tab") ?: 0,
                targetLineId = args?.getLong("line")?.takeIf { it != NO_ARG },
                cueAtMs = args?.getLong("at")?.takeIf { it != NO_ARG }
            )
        }
    }
}
