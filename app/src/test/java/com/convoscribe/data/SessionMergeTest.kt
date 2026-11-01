package com.convoscribe.data

import com.convoscribe.data.SessionManifest.ActionItem
import com.convoscribe.data.SessionManifest.FileRef
import com.convoscribe.data.SessionManifest.Line
import com.convoscribe.data.SessionManifest.Mark
import com.convoscribe.data.SessionManifest.Message
import com.convoscribe.data.SessionManifest.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three-way merge of a session changed in two places (issue #90). */
class SessionMergeTest {
    private val base = SessionManifest(
        syncId = "s",
        title = "Standup",
        titleSource = "user",
        createdAt = 1,
        durationMs = 3_000,
        language = "en",
        status = SessionStatus.DIARIZED.name,
        asrEngine = "engine",
        asrModel = "model",
        audio = FileRef("a".repeat(64), 10),
        lines = listOf(
            Line(0, 1_000, "one", speakerIndex = 0),
            Line(1_000, 2_000, "two", speakerIndex = 1),
            Line(2_000, 3_000, "three", speakerIndex = 0)
        ),
        speakers = listOf(Speaker(0, "Ann"), Speaker(1, "Bob")),
        messages = listOf(
            Message("SUMMARY", "ASSISTANT", "the summary", createdAt = 10),
            Message("QA", "USER", "why?", createdAt = 20),
            Message("QA", "ASSISTANT", "because", createdAt = 20)
        ),
        marks = listOf(Mark(100, "first", createdAt = 30), Mark(200, "second", createdAt = 31)),
        tags = listOf("home", "work"),
        actionItems = listOf(
            ActionItem("Call", "Call", createdAt = 40),
            ActionItem("Write", "Write", createdAt = 40)
        )
    )

    /** [local] (changed at 100) and [remote] (changed at 200) merged, so remote is later. */
    private fun merged(local: SessionManifest, remote: SessionManifest, from: SessionManifest? = base) =
        SessionMerge.merge(from, local, remote, 100, 200)

    /** Both orders must keep the same edits when the two changes do not overlap. */
    private fun assertBothWays(a: SessionManifest, b: SessionManifest, check: (SessionManifest) -> Unit) {
        check(SessionMerge.merge(base, a, b, 100, 200).manifest)
        check(SessionMerge.merge(base, b, a, 200, 100).manifest)
    }

    private fun SessionManifest.line(at: Int, text: String) =
        copy(lines = lines.toMutableList().also { it[at] = it[at].copy(text = text, textEdited = true) })

    private fun SessionManifest.item(at: Int, change: (ActionItem) -> ActionItem) =
        copy(actionItems = actionItems.toMutableList().also { it[at] = change(it[at]) })

    @Test
    fun aSideLeftAsTheBaseTakesTheOther() {
        val changed = base.copy(title = "Other", tags = listOf("x"))
        assertEquals(changed, merged(base, changed).manifest)
        assertEquals(changed, merged(changed, base).manifest)
        assertEquals(changed, merged(changed, changed).manifest)
    }

    @Test
    fun aTitleAndALineFromDifferentSidesBothSurvive() {
        assertBothWays(base.copy(title = "Renamed"), base.line(1, "corrected")) {
            assertEquals("Renamed", it.title)
            assertEquals("corrected", it.lines[1].text)
            assertTrue(it.lines[1].textEdited)
        }
    }

    @Test
    fun theTitleTravelsWithItsSource() {
        val auto = base.copy(title = "Auto", titleSource = "auto", previousTitle = "Standup")
        val result = merged(auto, base.line(0, "x")).manifest
        assertEquals("auto", result.titleSource)
        assertEquals("Standup", result.previousTitle)
    }

    @Test
    fun whenBothChangedAValueTheLaterWins() {
        val a = base.copy(title = "Earlier", language = "fr")
        val b = base.copy(title = "Later")
        assertEquals("Later", merged(a, b).manifest.title)
        assertEquals("fr", merged(a, b).manifest.language)
        assertEquals("Earlier", SessionMerge.merge(base, a, b, 300, 200).manifest.title)
    }

    @Test
    fun aTieSettlesOnTheSameValueFromEitherSide() {
        val a = base.copy(title = "A")
        val b = base.copy(title = "B")
        assertEquals(
            SessionMerge.merge(base, a, b, 100, 100).manifest,
            SessionMerge.merge(base, b, a, 100, 100).manifest
        )
    }

    @Test
    fun bothSidesEditingOneLineKeepsTheLaterTextAndTheOtherLines() {
        val result = merged(base.line(0, "earlier").line(2, "kept"), base.line(0, "later")).manifest
        assertEquals(listOf("later", "two", "kept"), result.lines.map { it.text })
    }

    @Test
    fun aLinesTextAndSpeakerMergeSeparately() {
        val speaker = base.copy(
            lines = base.lines.toMutableList().also { it[0] = it[0].copy(speakerIndex = 1, speakerEdited = true) }
        )
        assertBothWays(speaker, base.line(0, "text")) {
            assertEquals("text", it.lines[0].text)
            assertEquals(1, it.lines[0].speakerIndex)
            assertTrue(it.lines[0].speakerEdited)
        }
    }

    @Test
    fun tagsAddedAndRemovedOnDifferentSidesAllApply() {
        val a = base.copy(tags = listOf("added-a", "home"))
        val b = base.copy(tags = listOf("added-b", "home", "work"))
        assertBothWays(a, b) { assertEquals(listOf("added-a", "added-b", "home"), it.tags) }
    }

    @Test
    fun theSameTagAddedInDifferentCaseIsOneTag() {
        val a = base.copy(tags = base.tags + "New")
        val b = base.copy(tags = base.tags + "new")
        assertEquals(listOf("home", "new", "work"), merged(a, b).manifest.tags)
    }

    @Test
    fun speakersMergePerIndex() {
        val a = base.copy(speakers = listOf(Speaker(0, "Anna"), Speaker(1, "Bob")))
        val b = base.copy(speakers = listOf(Speaker(0, "Ann"), Speaker(1, "Rob")))
        assertBothWays(a, b) { assertEquals(listOf(Speaker(0, "Anna"), Speaker(1, "Rob")), it.speakers) }
    }

    @Test
    fun marksAndQuestionsAddedOnBothSidesAreAllKept() {
        val a = base.copy(
            marks = base.marks + Mark(300, "a", createdAt = 50),
            messages = base.messages + Message("QA", "USER", "from a", createdAt = 60)
        )
        val b = base.copy(
            marks = listOf(Mark(50, "b", createdAt = 51)) + base.marks,
            messages = base.messages + Message("QA", "USER", "from b", createdAt = 61)
        )
        assertBothWays(a, b) { result ->
            assertEquals(listOf(50L, 100L, 200L, 300L), result.marks.map { it.atMs })
            assertEquals(
                listOf("the summary", "why?", "because", "from a", "from b"),
                result.messages.map { it.content }
            )
        }
    }

    @Test
    fun aDeletedMarkOrClearedChatStaysGone() {
        val a = base.copy(marks = base.marks.drop(1), messages = base.messages.filter { it.kind == "SUMMARY" })
        val b = base.copy(title = "x", messages = base.messages + Message("QA", "USER", "new", createdAt = 70))
        assertBothWays(a, b) { result ->
            assertEquals(listOf(200L), result.marks.map { it.atMs })
            assertEquals(listOf("the summary", "new"), result.messages.map { it.content })
        }
    }

    @Test
    fun aMarksNoteAndExpansionMergeSeparately() {
        val a = base.copy(marks = listOf(base.marks[0].copy(note = "edited"), base.marks[1]))
        val b = base.copy(marks = listOf(base.marks[0].copy(expandedNote = "full", expandedAt = 5), base.marks[1]))
        assertBothWays(a, b) {
            assertEquals("edited", it.marks[0].note)
            assertEquals("full", it.marks[0].expandedNote)
        }
    }

    @Test
    fun twoSummariesAreOneValueNotTwo() {
        fun summary(text: String, at: Long) = base.copy(
            messages = listOf(Message("SUMMARY", "ASSISTANT", text, createdAt = at)) + base.messages.drop(1)
        )
        val result = merged(summary("earlier", 11), summary("later", 12)).manifest
        assertEquals(listOf("later"), result.messages.filter { it.kind == "SUMMARY" }.map { it.content })
    }

    @Test
    fun actionItemFieldsMergeAndADeleteSticks() {
        val a = base.item(0) { it.copy(done = true) }.item(1) { it.copy(deleted = true) }
        val b = base.item(0) { it.copy(text = "Phone", owner = "Ann", edited = true) }
            .item(1) { it.copy(text = "Draft", edited = true) }
        assertBothWays(a, b) { result ->
            assertEquals("Phone", result.actionItems[0].text)
            assertEquals("Ann", result.actionItems[0].owner)
            assertTrue(result.actionItems[0].done)
            assertTrue(result.actionItems[0].edited)
            assertTrue(result.actionItems[1].deleted)
        }
    }

    @Test
    fun anItemANewSummaryRemovedStaysIfTheOtherSideWorkedOnIt() {
        val regenerated = base.copy(actionItems = listOf(ActionItem("Plan", "Plan", createdAt = 90)))
        val ticked = base.item(0) { it.copy(done = true) }
        assertBothWays(regenerated, ticked) { result ->
            assertEquals(setOf("Call", "Plan"), result.actionItems.map { it.text }.toSet())
            assertTrue(result.actionItems.single { it.text == "Call" }.done)
        }
    }

    @Test
    fun twoNewSummariesDoNotListATaskTwice() {
        val a = base.copy(actionItems = listOf(ActionItem("Send the report", "Send the report", createdAt = 90)))
        val b = base.copy(actionItems = listOf(ActionItem("Send the report", "Send the report", createdAt = 91)))
        assertEquals(1, merged(a, b).manifest.actionItems.size)
        assertEquals(1, merged(b, a).manifest.actionItems.size)
    }

    @Test
    fun aDeletedItemDoesNotComeBackFromANewSummary() {
        val deleted = base.item(0) { it.copy(deleted = true) }
        val regenerated = base.copy(actionItems = listOf(ActionItem("Call", "Call", createdAt = 90)))
        assertBothWays(deleted, regenerated) { result ->
            assertEquals(listOf(true), result.actionItems.filter { it.text == "Call" }.map { it.deleted })
        }
    }

    @Test
    fun aContinuedRecordingWinsWholeAndEverythingElseMerges() {
        val continued = base.copy(
            durationMs = 9_000,
            audio = FileRef("b".repeat(64), 30),
            lines = base.lines + Line(3_000, 9_000, "more")
        )
        val edited = base.line(0, "lost").copy(title = "Renamed", tags = base.tags + "new")
        assertBothWays(continued, edited) { result ->
            assertEquals(9_000, result.durationMs)
            assertEquals(continued.audio, result.audio)
            assertEquals(continued.lines, result.lines)
            assertEquals("Renamed", result.title)
            assertEquals(listOf("home", "new", "work"), result.tags)
        }
    }

    @Test
    fun aRetranscriptionWinsWholeAndReportsTheEditsItReplaced() {
        val again = base.copy(
            asrModel = "better",
            lines = listOf(Line(0, 3_000, "all of it")),
            status = SessionStatus.TRANSCRIBED.name
        )
        val result = merged(again, base.line(1, "lost"))
        assertEquals(again.lines, result.manifest.lines)
        assertEquals("better", result.manifest.asrModel)
        assertEquals(listOf("line 1000-2000 ms"), result.dropped)
    }

    @Test
    fun whenBothRewroteTheLongerRecordingWins() {
        val longer = base.copy(durationMs = 9_000, audio = FileRef("b".repeat(64), 30))
        val later = base.copy(durationMs = 5_000, audio = FileRef("c".repeat(64), 20))
        assertEquals(longer.audio, merged(longer, later).manifest.audio)
        assertEquals(longer.audio, merged(later, longer).manifest.audio)
        val equalLength = later.copy(audio = FileRef("d".repeat(64), 20))
        assertEquals(equalLength.audio, merged(later, equalLength).manifest.audio)
    }

    @Test
    fun removingACleanedCopyIsNotARewrite() {
        val cleaned = base.copy(cleanedWith = "cleaner", cleanAudio = FileRef("c".repeat(64), 5))
        val removed = cleaned.copy(cleanedWith = null, cleanAudio = null)
        val result = SessionMerge.merge(cleaned, removed, cleaned.line(0, "kept"), 100, 200)
        assertEquals(null, result.manifest.cleanedWith)
        assertEquals("kept", result.manifest.lines[0].text)
    }

    @Test
    fun aSplitsStructureWinsAndOtherEditsCarryOver() {
        val split = base.copy(
            lines = listOf(
                base.lines[0],
                Line(1_000, 1_500, "tw"),
                Line(1_500, 2_000, "o"),
                base.lines[2]
            )
        )
        val edited = base.line(0, "first").line(2, "last")
        assertBothWays(split, edited) { result ->
            assertEquals(listOf("first", "tw", "o", "last"), result.lines.map { it.text })
        }
    }

    @Test
    fun anEditToALineTheOtherSideSplitIsReported() {
        val merged = base.copy(lines = listOf(Line(0, 2_000, "one two"), base.lines[2]))
        val edited = base.line(1, "lost")
        val result = merged(merged, edited)
        assertEquals(listOf("one two", "three"), result.manifest.lines.map { it.text })
        assertEquals(listOf("line 1000-2000 ms"), result.dropped)
        val other = merged(edited, merged)
        assertEquals(listOf("one two", "three"), other.manifest.lines.map { it.text })
        assertEquals(listOf("line 1000-2000 ms"), other.dropped)
    }

    @Test
    fun aMergeOfUneditedLinesReportsNothing() {
        val merged = base.copy(lines = listOf(Line(0, 2_000, "one two"), base.lines[2]))
        assertTrue(merged(merged, base.copy(title = "x")).dropped.isEmpty())
    }

    @Test
    fun whenBothChangedTheStructureTheLaterWins() {
        val a = base.copy(lines = listOf(Line(0, 2_000, "one two"), base.lines[2]))
        val b = base.copy(lines = listOf(base.lines[0], Line(1_000, 3_000, "two three")))
        assertEquals(listOf("one", "two three"), merged(a, b).manifest.lines.map { it.text })
    }

    @Test
    fun identifyingSpeakersAgainWinsItsNumberingButKeepsTextEdits() {
        val again = base.copy(
            diarizedWith = "model",
            speakers = listOf(Speaker(0, "Speaker 1")),
            lines = base.lines.map { it.copy(speakerIndex = 0) }
        )
        val other = base.line(1, "edited").copy(speakers = listOf(Speaker(0, "Anna"), Speaker(1, "Bob")))
        assertBothWays(again, other) { result ->
            assertEquals(again.speakers, result.speakers)
            assertEquals(listOf(0, 0, 0), result.lines.map { it.speakerIndex })
            assertEquals("edited", result.lines[1].text)
            assertEquals("model", result.diarizedWith)
        }
    }

    @Test
    fun withoutABaseTheLaterSideWinsValuesAndAdditionsCombine() {
        val a = base.line(0, "earlier").copy(
            title = "Earlier",
            tags = listOf("a"),
            marks = listOf(Mark(1, "a", createdAt = 1))
        )
        val b = base.line(0, "later").copy(
            title = "Later",
            tags = listOf("b"),
            marks = listOf(Mark(2, "b", createdAt = 2))
        )
        val result = merged(a, b, from = null).manifest
        assertEquals("Later", result.title)
        assertEquals("later", result.lines[0].text)
        assertEquals(listOf("a", "b"), result.tags)
        assertEquals(2, result.marks.size)
    }

    @Test
    fun withoutABaseTheLongerRecordingStillWins() {
        val longer = base.line(0, "longer").copy(durationMs = 9_000)
        val later = base.line(0, "later")
        val result = merged(longer, later, from = null).manifest
        assertEquals(9_000, result.durationMs)
        assertEquals("longer", result.lines[0].text)
    }

    @Test
    fun aRepeatedMergeChangesNothing() {
        val a = base.copy(title = "A", tags = listOf("x"))
        val b = base.line(0, "b").item(0) { it.copy(done = true) }
        val once = merged(a, b).manifest
        assertEquals(once, merged(once, b).manifest)
        assertEquals(once, merged(a, once).manifest)
    }

    @Test
    fun rowsWithTheSameTimesStayApart() {
        val qa = listOf(Message("QA", "USER", "one", createdAt = 5), Message("QA", "USER", "two", createdAt = 5))
        val a = base.copy(messages = qa, title = "a")
        val b = base.copy(messages = qa, tags = listOf("b"))
        assertEquals(listOf("one", "two"), merged(a, b).manifest.messages.map { it.content })
    }

    /** A new manifest field needs a rule in [SessionMerge]: add it there, then here. */
    @Test
    fun everyManifestFieldHasARule() {
        val descriptor = SessionManifest.serializer().descriptor
        assertEquals(
            setOf(
                "schema", "syncId", "title", "titleSource", "previousTitle", "createdAt", "durationMs", "language",
                "status", "numSpeakers", "participants", "asrEngine", "asrModel", "transcriptEdited",
                "summaryTemplate", "cleanedWith", "diarizedWith", "diarizationStepMs", "diarizationMs", "audio",
                "cleanAudio", "lines", "speakers", "messages", "marks", "tags", "actionItems"
            ),
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        )
    }

    @Test
    fun plainFieldsTheOtherSideLeftAloneSurvive() {
        val ref = SessionManifest.TemplateRef(key = "k")
        val a = base.copy(
            numSpeakers = 3,
            participants = listOf("Ann"),
            summaryTemplate = ref,
            language = "fr",
            transcriptEdited = true
        )
        val result = merged(a, base.copy(title = "x")).manifest
        assertEquals(3, result.numSpeakers)
        assertEquals(listOf("Ann"), result.participants)
        assertEquals(ref, result.summaryTemplate)
        assertEquals("fr", result.language)
        assertTrue(result.transcriptEdited)
        assertFalse(merged(base.copy(tags = emptyList()), base.copy(title = "x")).manifest.transcriptEdited)
    }
}
