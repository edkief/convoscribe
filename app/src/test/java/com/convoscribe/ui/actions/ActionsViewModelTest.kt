package com.convoscribe.ui.actions

import com.convoscribe.data.ActionItemEntity
import com.convoscribe.data.OpenActionItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** How *Open actions* groups the open items (issue #31). */
class ActionsViewModelTest {

    private fun open(id: Long, sessionId: Long, owner: String?, title: String = "Session $sessionId") = OpenActionItem(
        item = ActionItemEntity(
            id = id,
            sessionId = sessionId,
            text = "Item $id",
            extractedText = "Item $id",
            owner = owner,
            position = id.toInt(),
            createdAt = 0
        ),
        sessionTitle = title,
        sessionCreatedAt = 1_000L * sessionId
    )

    // As the DAO lists them: newest session first, then in each summary's order.
    private val items = listOf(
        open(1, sessionId = 2, owner = "Élodie"),
        open(2, sessionId = 2, owner = null),
        open(3, sessionId = 1, owner = "bob"),
        open(4, sessionId = 1, owner = "elodie ")
    )

    @Test
    fun bySessionKeepsTheNewestSessionFirst() {
        val groups = ActionsViewModel.group(items, ActionsGrouping.SESSION)
        assertEquals(listOf("Session 2", "Session 1"), groups.map { it.title })
        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L)), groups.map { g -> g.items.map { it.item.id } })
        assertEquals(listOf(2_000L, 1_000L), groups.map { it.createdAt })
    }

    @Test
    fun byOwnerIsAlphabeticalWithNoOwnerLast() {
        val groups = ActionsViewModel.group(items, ActionsGrouping.OWNER)
        assertEquals(listOf("bob", "Élodie", null), groups.map { it.title })
        assertEquals(listOf(listOf(3L), listOf(1L, 4L), listOf(2L)), groups.map { g -> g.items.map { it.item.id } })
    }

    @Test
    fun nothingOpenMeansNoGroups() {
        assertEquals(emptyList<ActionGroup>(), ActionsViewModel.group(emptyList(), ActionsGrouping.OWNER))
    }
}
