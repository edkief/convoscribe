package com.convoscribe.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.ui.actions.ActionsScreen
import com.convoscribe.ui.ask.AskAcrossScreen
import com.convoscribe.ui.organise.TalkingPointListsScreen
import com.convoscribe.ui.theme.AppTheme

/** Home's top-level sections (issue #108), in the order the bar shows them. */
enum class HomeSection(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    RECORDINGS("recordings", R.string.home_section_recordings, R.drawable.ic_mic),
    ACTIONS("actions", R.string.home_section_actions, R.drawable.ic_task),
    TALKING_POINTS("talking-points", R.string.home_section_talking_points, R.drawable.ic_list),
    ASK("ask", R.string.home_section_ask, R.drawable.ic_conversation);

    companion object {
        fun of(key: String?): HomeSection = entries.firstOrNull { it.key == key } ?: RECORDINGS
    }
}

/** Key in the `home` entry's [SavedStateHandle] holding the shown [HomeSection]'s key. */
const val HOME_SECTION_KEY = "section"

/**
 * The `home` route (issue #108): a bar of [HomeSection]s with the
 * chosen one above it. The section lives in the entry's
 * [savedStateHandle], so it survives rotation and process death, and
 * another screen can pick one before popping back to `home`
 * ([openHomeSection]). Each section's own state (scroll, drafts) is
 * kept while another is shown; their ViewModels belong to the `home`
 * entry, so they outlive switching too.
 *
 * Back from any section but Recordings goes to Recordings; from
 * Recordings it leaves the app.
 */
@Composable
fun HomeShell(navController: NavHostController, savedStateHandle: SavedStateHandle) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val key by savedStateHandle.getStateFlow<String?>(HOME_SECTION_KEY, null).collectAsStateWithLifecycle()
    val section = HomeSection.of(key)
    val openActionsFlow = remember { container.sessionRepository.observeOpenActionCount() }
    val openActions by openActionsFlow.collectAsStateWithLifecycle(initialValue = 0)
    val select: (HomeSection) -> Unit = { savedStateHandle[HOME_SECTION_KEY] = it.key }
    BackHandler(enabled = section != HomeSection.RECORDINGS) { select(HomeSection.RECORDINGS) }
    val states = rememberSaveableStateHolder()
    HomeShellFrame(section = section, openActions = openActions, onSelect = select) {
        states.SaveableStateProvider(section.key) {
            when (section) {
                HomeSection.RECORDINGS -> HomeScreen(navController = navController)
                HomeSection.ACTIONS -> ActionsScreen(navController = navController, onBack = null)
                HomeSection.TALKING_POINTS -> TalkingPointListsScreen(navController = navController, onBack = null)
                HomeSection.ASK -> AskAcrossScreen(navController = navController, onBack = null)
            }
        }
    }
}

/**
 * Go back to `home` showing [section]: from a screen pushed on top of
 * it, such as Search's *Ask across sessions*.
 */
fun NavHostController.openHomeSection(section: HomeSection) {
    val home = runCatching { getBackStackEntry("home") }.getOrNull()
    if (home == null) {
        navigate("home")
        return
    }
    home.savedStateHandle[HOME_SECTION_KEY] = section.key
    popBackStack("home", inclusive = false)
}

/**
 * Render-only frame of [HomeShell], for screenshot tests: a bottom
 * navigation bar under [content], or a navigation rail beside it from
 * [WIDE_SCREEN_DP] (or when [wide] says so). Actions carries a badge
 * with [openActions] when there are any. The section's own screen
 * draws its top bar; the frame consumes the bar's insets so that
 * screen doesn't pad for the system navigation bar again.
 */
@Composable
internal fun HomeShellFrame(
    section: HomeSection,
    openActions: Int,
    onSelect: (HomeSection) -> Unit,
    wide: Boolean = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP,
    content: @Composable () -> Unit
) {
    val eink = AppTheme.isEink
    if (wide) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                HomeSection.entries.forEach { item ->
                    NavigationRailItem(
                        selected = item == section,
                        onClick = { onSelect(item) },
                        icon = { BarText { SectionIcon(item, openActions) } },
                        label = {
                            BarText {
                                Text(
                                    text = stringResource(item.label),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(max = RAIL_LABEL_MAX_WIDTH)
                                )
                            }
                        }
                    )
                }
            }
            if (eink) VerticalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxHeight())
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column {
                    if (eink) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = if (eink) AppTheme.sizes.elevation else NavigationBarDefaults.Elevation
                    ) {
                        HomeSection.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == section,
                                onClick = { onSelect(item) },
                                icon = { BarText { SectionIcon(item, openActions) } },
                                label = {
                                    BarText {
                                        Text(
                                            text = stringResource(item.label),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { padding: PaddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
            ) { content() }
        }
    }
}

/**
 * [content] with the font scale capped at [MAX_BAR_FONT_SCALE]: four
 * labels side by side cannot grow without bound, so the bar's labels
 * and badge stop at that size while the sections' content keeps the
 * user's full scale.
 */
@Composable
private fun BarText(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = Density(density.density, minOf(density.fontScale, MAX_BAR_FONT_SCALE))
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

/** The section's icon; Actions with a badge counting open items. */
@Composable
private fun SectionIcon(section: HomeSection, openActions: Int) {
    val icon = @Composable { Icon(painter = painterResource(section.icon), contentDescription = null) }
    if (section == HomeSection.ACTIONS && openActions > 0) {
        val description = pluralStringResource(R.plurals.home_open_actions_badge, openActions, openActions)
        BadgedBox(
            badge = {
                Badge(modifier = Modifier.semantics { contentDescription = description }) {
                    Text(text = if (openActions > BADGE_MAX) "$BADGE_MAX+" else openActions.toString())
                }
            }
        ) { icon() }
    } else {
        icon()
    }
}

/** Largest font scale the bar's labels and badge follow. */
private const val MAX_BAR_FONT_SCALE = 1.3f

/** A rail label wider than this wraps onto a second line. */
private val RAIL_LABEL_MAX_WIDTH = 72.dp

/** Past this many open items the badge says "99+". */
private const val BADGE_MAX = 99
