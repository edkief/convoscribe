package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ActionItemsParser] (issue #31): the JSON contract first, the bullet list when it fails. */
class ActionItemsParserTest {

    private val prose = "**Overview**\nWe planned the launch.\n\n" +
        "**Action items**\n- Alice: send the deck [02:10]\n- Bob books the room by 2026-11-06\n"

    @Test
    fun jsonBlockGivesTheItemsAndIsTakenOffTheProse() {
        val text = prose + "\n```json\n" +
            "[{\"text\": \"Send the deck\", \"owner\": \"alice\", \"due\": null, \"at\": \"02:10\"}," +
            " {\"text\": \"Book the room\", \"owner\": \"Bob\", \"due\": \"2026-11-06\", \"at\": null}]\n```\n"

        val parsed = ActionItemsParser.split(text, knownNames = listOf("Alice", "Bob"))

        assertTrue(parsed.fromJson)
        assertEquals(prose.trimEnd(), parsed.prose)
        assertEquals(
            listOf(
                ExtractedActionItem("Send the deck", owner = "Alice", atMs = 130_000L),
                ExtractedActionItem("Book the room", owner = "Bob", due = "2026-11-06")
            ),
            parsed.items
        )
    }

    @Test
    fun emptyArrayMeansNoItemsEvenWithAnActionsSection() {
        val parsed = ActionItemsParser.split(prose + "\n```json\n[]\n```")
        assertTrue(parsed.fromJson)
        assertEquals(emptyList<ExtractedActionItem>(), parsed.items)
        assertEquals(prose.trimEnd(), parsed.prose)
    }

    @Test
    fun bareFenceAndLenientJsonAreAccepted() {
        val text = "Summary.\n```\n[{text: \"Call the bank\", owner: null, at: \"[1:02:03]\"}]\n```"
        val parsed = ActionItemsParser.split(text)
        assertTrue(parsed.fromJson)
        assertEquals("Summary.", parsed.prose)
        assertEquals(listOf(ExtractedActionItem("Call the bank", atMs = 3_723_000L)), parsed.items)
    }

    @Test
    fun objectHoldingTheArrayIsAccepted() {
        val text = "Summary.\n```json\n{\"action_items\": [{\"text\": \"Ship it\"}]}\n```"
        assertEquals(listOf(ExtractedActionItem("Ship it")), ActionItemsParser.split(text).items)
    }

    @Test
    fun badFieldsAreDroppedNotFatal() {
        val text = "Summary.\n```json\n[{\"text\": \"Ship it\", \"due\": \"next Friday\", \"at\": \"soon\"}," +
            " {\"owner\": \"Bob\"}, \"loose\", {\"text\": \"  \"}]\n```"
        assertEquals(listOf(ExtractedActionItem("Ship it")), ActionItemsParser.split(text).items)
    }

    @Test
    fun malformedJsonFallsBackToTheBulletList() {
        val text = prose + "\n```json\n[{\"text\": \"Send the deck\", owner: }\n```"

        val parsed = ActionItemsParser.split(text, knownNames = listOf("Alice", "Bob"))

        assertFalse(parsed.fromJson)
        assertEquals(prose.trimEnd(), parsed.prose)
        assertEquals(
            listOf(
                ExtractedActionItem("send the deck", owner = "Alice", atMs = 130_000L),
                ExtractedActionItem("Bob books the room by 2026-11-06", owner = "Bob", due = "2026-11-06")
            ),
            parsed.items
        )
    }

    @Test
    fun truncatedBlockIsStillTakenOffAndFallsBack() {
        val parsed = ActionItemsParser.split(prose + "\n```json\n[{\"text\": \"Send the")
        assertFalse(parsed.fromJson)
        assertEquals(prose.trimEnd(), parsed.prose)
        assertEquals(2, parsed.items.size)
    }

    @Test
    fun noBlockReadsTheActionItemsSectionOnly() {
        val text = "## Key points\n- Budget is fine\n\n## Action items\n1. Dana: draft the contract\n" +
            "2. Review pricing (Marc)\n\n## Notes\n- Not an item\n"
        val parsed = ActionItemsParser.split(text)
        assertFalse(parsed.fromJson)
        assertEquals(text.trimEnd(), parsed.prose)
        assertEquals(
            listOf(
                ExtractedActionItem("draft the contract", owner = "Dana"),
                ExtractedActionItem("Review pricing", owner = "Marc")
            ),
            parsed.items
        )
    }

    @Test
    fun numberedSectionsEndTheList() {
        val text = "4. Action items:\n- Send the deck\n5. Follow-ups:\n- Not an item\n"
        assertEquals(listOf(ExtractedActionItem("Send the deck")), ActionItemsParser.split(text).items)
    }

    @Test
    fun noneMeansNoItems() {
        assertEquals(emptyList<ExtractedActionItem>(), ActionItemsParser.split("4. Action items: None").items)
        assertEquals(emptyList<ExtractedActionItem>(), ActionItemsParser.split("**Action items**\n- None.\n").items)
        assertEquals(emptyList<ExtractedActionItem>(), ActionItemsParser.split("**Actions à mener**\n- Aucune\n").items)
    }

    @Test
    fun frenchHeadingIsFound() {
        val text = "**Points clés**\n- Le budget\n\n**Actions à mener**\n- Élodie envoie le devis [00:45]\n"
        assertEquals(
            listOf(ExtractedActionItem("Élodie envoie le devis", owner = "Élodie", atMs = 45_000L)),
            ActionItemsParser.split(text, knownNames = listOf("Élodie")).items
        )
    }

    @Test
    fun summaryWithoutActionsHasNone() {
        val parsed = ActionItemsParser.split("- Point one\n- Point two\n")
        assertEquals(emptyList<ExtractedActionItem>(), parsed.items)
        assertEquals("- Point one\n- Point two", parsed.prose)
    }

    @Test
    fun clockTimesInBulletsAreNotTimestamps() {
        val item = ActionItemsParser.split("**Action items**\n- Call Bob at 14:30\n").items.single()
        assertNull(item.atMs)
    }

    @Test
    fun ownersMatchKnownNamesIgnoringCaseAndAccents() {
        val names = listOf("Élodie Martin", "Bob")
        assertEquals("Élodie Martin", ActionItemsParser.owner("elodie martin", names))
        assertEquals("Bob", ActionItemsParser.owner("Bob (sales)", names))
        assertEquals("Marketing team", ActionItemsParser.owner("Marketing team", names))
        assertNull(ActionItemsParser.owner("None", names))
        assertNull(ActionItemsParser.owner("  ", names))
    }

    @Test
    fun visibleHidesTheBlockWhileItStreams() {
        val head = "Summary text."
        assertEquals(head, ActionItemsParser.visible("$head\n`"))
        assertEquals(head, ActionItemsParser.visible("$head\n``"))
        assertEquals(head, ActionItemsParser.visible("$head\n```"))
        assertEquals(head, ActionItemsParser.visible("$head\n```js"))
        assertEquals(head, ActionItemsParser.visible("$head\n```json\n[{\"text\": \"Se"))
        assertEquals(head, ActionItemsParser.visible("$head\n```json\n[]\n```"))
        assertEquals("$head\n", ActionItemsParser.visible("$head\n"))
        val code = "Run:\n```\nls\n```\nThen go."
        assertEquals(code, ActionItemsParser.visible(code))
    }
}
