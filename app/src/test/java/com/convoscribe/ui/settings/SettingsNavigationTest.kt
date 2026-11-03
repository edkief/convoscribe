package com.convoscribe.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.R
import com.convoscribe.ui.settingsRoute
import com.convoscribe.ui.theme.ConvoScribeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The overview and its pages (issue #98): moving between them, what is saved, what stays closed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class SettingsNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(id)

    /** Hoists the page as [SettingsScreen] does; [onLeavePage] stands in for its save. */
    private fun setBody(initial: SettingsPage? = null, onLeavePage: () -> Unit = {}, onLeave: () -> Unit = {}) {
        compose.setContent {
            ConvoScribeTheme {
                var key by rememberSaveable { mutableStateOf(initial?.key) }
                SettingsBody(
                    state = SettingsUiState(),
                    actions = SettingsActions(onBack = onLeave),
                    page = SettingsPage.of(key),
                    onPageChange = {
                        onLeavePage()
                        key = it?.key
                    }
                )
            }
        }
    }

    private fun back() = compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()

    @Test
    fun `a row opens its page and back returns to the overview and then leaves`() {
        var left = 0
        setBody(onLeave = { left++ })
        compose.onNodeWithText(text(R.string.settings_page_speakers)).performClick()
        compose.onNodeWithText(text(R.string.settings_speakers_known_row)).assertIsDisplayed()

        back()
        compose.onNodeWithText(text(R.string.settings_group_capture)).assertIsDisplayed()
        assertEquals(0, left)

        back()
        assertEquals(1, left)
    }

    @Test
    fun `leaving a page saves, so nothing typed is lost`() {
        var saves = 0
        setBody(initial = SettingsPage.AI, onLeavePage = { saves++ })
        back()
        assertEquals(1, saves)
    }

    @Test
    fun `the overview fits the screen without scrolling`() {
        setBody()
        val last = compose.onNodeWithText(text(R.string.settings_page_about)).fetchSemanticsNode()
        val window = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("${last.boundsInRoot.bottom} of $window", last.boundsInRoot.bottom < window)
    }

    @Test
    fun `the page survives recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ConvoScribeTheme {
                var key by rememberSaveable { mutableStateOf<String?>(null) }
                SettingsBody(
                    state = SettingsUiState(),
                    actions = SettingsActions(),
                    page = SettingsPage.of(key),
                    onPageChange = { key = it?.key }
                )
            }
        }
        compose.onNodeWithText(text(R.string.settings_page_speakers)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.settings_speakers_known_row)).assertIsDisplayed()
    }

    @Test
    fun `advanced is closed at first, says so, and opens in place`() {
        setBody(initial = SettingsPage.SPEAKERS)
        val threshold = text(R.string.settings_speakers_threshold_label)
        compose.onNodeWithText(threshold).assertDoesNotExist()

        val advanced = compose.onNodeWithText(text(R.string.settings_advanced))
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.disclosure_collapsed))
        ).assertExists()
        advanced.performScrollTo().performClick()

        compose.onNodeWithText(threshold).assertExists()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.disclosure_expanded))
        ).assertExists()
    }

    @Test
    fun `transcription source is on the recording page, not the server page`() {
        setBody(initial = SettingsPage.SERVER)
        compose.onNodeWithText(text(R.string.sync_section)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_section_server)).assertDoesNotExist()
    }

    @Test
    fun `the route opens the page it names, and the plain route the overview`() {
        var page: String? = "unset"
        lateinit var navigate: (String) -> Unit
        compose.setContent {
            val nav = rememberNavController()
            navigate = { nav.navigate(it) }
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {}
                composable(
                    route = "settings?page={page}",
                    arguments = listOf(
                        navArgument("page") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { entry -> page = entry.arguments?.getString("page") }
            }
        }
        compose.runOnIdle { navigate(settingsRoute(SettingsPage.AI)) }
        compose.waitForIdle()
        assertEquals("ai", page)

        compose.runOnIdle { navigate(settingsRoute()) }
        compose.waitForIdle()
        assertEquals(null, page)
    }
}
