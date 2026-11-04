package com.convoscribe.ui.organise

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.convoscribe.data.TalkingPointList
import com.convoscribe.ui.theme.ConvoScribeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The list editor's rows (issue #111): buttons that name their point, order kept through rotation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class TalkingPointListEditorTest {

    @get:Rule
    val compose = createComposeRule()

    private val list = TalkingPointList(1, "Weekly", listOf("Budget", "Hiring", "Office"))

    @Test
    fun movesRemovesAndAddsThenSavesInThatOrder() {
        var saved: TalkingPointListDraft? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ConvoScribeTheme {
                TalkingPointListsBody(
                    lists = listOf(list),
                    onBack = null,
                    onSave = { saved = it },
                    onDelete = {},
                    initialDraft = TalkingPointListDraft.of(list)
                )
            }
        }
        compose.onNodeWithContentDescription("Move “Budget” up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move “Office” down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move “Office” up").performClick()
        compose.onNodeWithContentDescription("Remove “Budget”").performClick()
        compose.onNodeWithText("Add point").performScrollTo().performClick()
        // A blank point's buttons use its number.
        compose.onNodeWithContentDescription("Remove “Point 3”").assertExists()
        compose.onNodeWithText("Point 3").performTextInput("Parking")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("Office", "Hiring", "Parking"), saved?.savedPoints)
    }
}
