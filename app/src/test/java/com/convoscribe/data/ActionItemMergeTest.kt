package com.convoscribe.data

import com.convoscribe.llm.ExtractedActionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ActionItemMerge] (issue #31): regenerating keeps what the user did and adds nothing twice. */
class ActionItemMergeTest {

    private fun item(
        id: Long,
        text: String,
        extracted: String = text,
        done: Boolean = false,
        edited: Boolean = false,
        deleted: Boolean = false,
        position: Int = id.toInt()
    ) = ActionItemEntity(
        id = id,
        sessionId = 1,
        text = text,
        extractedText = extracted,
        done = done,
        edited = edited,
        deleted = deleted,
        position = position,
        createdAt = 0
    )

    @Test
    fun firstSummaryAddsEveryItemInOrder() {
        val result = ActionItemMerge.merge(
            1,
            emptyList(),
            listOf(
                ExtractedActionItem("Send the deck", owner = "Ann", due = "2026-11-06", atMs = 5),
                ExtractedActionItem("Book")
            ),
            now = 9
        )
        assertEquals(emptyList<Long>(), result.removeIds)
        assertEquals(
            listOf(
                ActionItemEntity(
                    sessionId = 1,
                    text = "Send the deck",
                    extractedText = "Send the deck",
                    owner = "Ann",
                    dueDate = "2026-11-06",
                    atMs = 5,
                    position = 0,
                    createdAt = 9
                ),
                ActionItemEntity(sessionId = 1, text = "Book", extractedText = "Book", position = 1, createdAt = 9)
            ),
            result.add
        )
    }

    @Test
    fun untouchedItemsAreReplacedAndTouchedOnesKept() {
        val existing = listOf(
            item(1, "Send the deck", done = true),
            item(2, "Book the room"),
            item(3, "Call the bank", deleted = true),
            item(4, "Draft the big contract", extracted = "Draft the contract", edited = true)
        )
        val result = ActionItemMerge.merge(
            1,
            existing,
            listOf(
                ExtractedActionItem("send the deck."),
                ExtractedActionItem("Book the room"),
                ExtractedActionItem("Call the bank"),
                ExtractedActionItem("Draft the contract"),
                ExtractedActionItem("Hire a designer"),
                ExtractedActionItem("Hire a designer!")
            ),
            now = 9
        )
        assertEquals(listOf(2L), result.removeIds)
        assertEquals(listOf("Book the room", "Hire a designer"), result.add.map { it.text })
        assertEquals(listOf(5, 6), result.add.map { it.position })
    }

    @Test
    fun anEmptyNewListDropsOnlyTheModelsItems() {
        val result = ActionItemMerge.merge(1, listOf(item(1, "A", done = true), item(2, "B")), emptyList(), 9)
        assertEquals(listOf(2L), result.removeIds)
        assertEquals(emptyList<ActionItemEntity>(), result.add)
    }

    @Test
    fun sameIgnoresCaseAccentsPunctuationAndSmallRewordings() {
        assertTrue(ActionItemMerge.same("Envoyer le devis à Élodie", "envoyer le devis a elodie"))
        assertTrue(ActionItemMerge.same("Send Bob the deck", "Send the deck to Bob"))
        assertTrue(ActionItemMerge.same("Send the deck", "Send the slide deck"))
        assertFalse(ActionItemMerge.same("Send the deck", "Book the room"))
        assertFalse(ActionItemMerge.same("Call Ann", "Call Bob"))
    }
}
