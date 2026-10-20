package com.convoscribe.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.convoscribe.asr.TimedToken
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SessionRepository]. Robolectric gives us a real
 * Android [Context] (so [androidx.room.Room] can wire SQLite), and
 * `allowMainThreadQueries()` keeps the DAO calls synchronous so we
 * can assert on them directly without a dispatcher dance.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Use a fresh directory under the test app's files dir for
        // each test so we can assert on file lifecycle.
        recordingsDir = File(context.filesDir, "recordings-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        db.close()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    @Test
    fun createSession_returnsIdAndStampsAudioPath() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        assertTrue("id should be > 0", id > 0)

        val row = repo.getSession(id)
        assertNotNull(row)
        assertEquals("Test", row!!.title)
        assertEquals("en", row.language)
        assertEquals(SessionStatus.RECORDING, row.status)
        assertTrue(
            "audioPath should end with /<id>.wav, was ${row.audioPath}",
            row.audioPath.endsWith("/$id.wav")
        )
    }

    @Test
    fun observeSegments_returnsRowsOrderedByStartMs() = runTest {
        val id = repo.createSession(title = "Test", language = "en")

        // Insert out of order to prove the ORDER BY is doing the work.
        repo.addSegment(id, startMs = 500, endMs = 1000, text = "second")
        repo.addSegment(id, startMs = 0, endMs = 500, text = "first")
        repo.addSegment(id, startMs = 1200, endMs = 1500, text = "third")

        val segments = repo.observeSegments(id).first()
        assertEquals(listOf("first", "second", "third"), segments.map { it.text })
    }

    @Test
    fun observeSegments_emitsViaFlowWhenNewRowsArrive() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0, endMs = 100, text = "a")

        repo.observeSegments(id).test {
            val first = awaitItem()
            assertEquals(listOf("a"), first.map { it.text })

            repo.addSegment(id, startMs = 100, endMs = 200, text = "b")
            val second = awaitItem()
            assertEquals(listOf("a", "b"), second.map { it.text })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun applyDiarization_setsSpeakerIndexCreatesLabelsAndStatus() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val s1 = repo.addSegment(id, startMs = 0, endMs = 1000, text = "hello")
        val s2 = repo.addSegment(id, startMs = 1000, endMs = 2000, text = "world")
        val s3 = repo.addSegment(id, startMs = 2000, endMs = 3000, text = "...")

        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(s1 to 0, s2 to 1, s3 to null),
            speakerCount = 2
        )

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)

        val segments = repo.getSegments(id)
        assertEquals(listOf(0, 1, null), segments.map { it.speakerIndex })

        val speakers = repo.getSpeakers(id)
        assertEquals(
            listOf("Speaker 1" to 0, "Speaker 2" to 1),
            speakers.map { it.label to it.speakerIndex }
        )
    }

    @Test
    fun applyDiarization_calledAgainReplacesSpeakers() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val s1 = repo.addSegment(id, startMs = 0, endMs = 1000, text = "a")
        val s2 = repo.addSegment(id, startMs = 1000, endMs = 2000, text = "b")

        // First diarization: 3 speakers.
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(s1 to 2, s2 to 0),
            speakerCount = 3
        )
        val first = repo.getSpeakers(id)
        assertEquals(3, first.size)
        assertEquals(
            listOf("Speaker 1", "Speaker 2", "Speaker 3"),
            first.map { it.label }
        )

        // Second diarization: only 1 speaker. Old speaker rows must
        // be gone, segments must point at index 0 (or null).
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(s1 to 0, s2 to 0),
            speakerCount = 1
        )
        val second = repo.getSpeakers(id)
        assertEquals(1, second.size)
        assertEquals("Speaker 1", second[0].label)
        assertEquals(0, second[0].speakerIndex)
        assertEquals(listOf(0, 0), repo.getSegments(id).map { it.speakerIndex })
    }

    @Test
    fun deleteSession_removesSessionAndCascadesRowsAndAudioFile() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        // Create the WAV file the way the recorder would, so
        // deleteSession has something to remove.
        val audio = File(recordingsDir, "$id.wav")
        audio.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(audio.exists())

        val segId = repo.addSegment(id, startMs = 0, endMs = 100, text = "x")
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segId to 0),
            speakerCount = 1
        )
        repo.addChatMessage(
            ChatMessageEntity(
                sessionId = id,
                kind = MessageKind.SUMMARY,
                role = MessageRole.ASSISTANT,
                content = "summary",
                createdAt = 1L
            )
        )
        repo.addChatMessage(
            ChatMessageEntity(
                sessionId = id,
                kind = MessageKind.QA,
                role = MessageRole.USER,
                content = "Q?",
                createdAt = 2L
            )
        )

        repo.deleteSession(id)

        // Session row gone.
        assertNull(repo.getSession(id))
        // Cascade: no segments, no speakers, no chat messages.
        assertTrue(repo.getSegments(id).isEmpty())
        assertTrue(repo.getSpeakers(id).isEmpty())
        // Chat is observed by kind; both kinds must be empty.
        assertEquals(emptyList<ChatMessageEntity>(), repo.observeChat(id, MessageKind.SUMMARY).first())
        assertEquals(emptyList<ChatMessageEntity>(), repo.observeChat(id, MessageKind.QA).first())
        // WAV file removed.
        assertFalse(audio.exists())
    }

    @Test
    fun deleteSession_unknownIdIsANoOp() = runTest {
        // No exception, no row created.
        repo.deleteSession(99999L)
        assertNull(repo.getSession(99999L))
    }

    @Test
    fun finishRecording_setsDurationAndStatus() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.finishRecording(id, durationMs = 12_345L)
        val row = repo.getSession(id)!!
        assertEquals(12_345L, row.durationMs)
        assertEquals(SessionStatus.TRANSCRIBED, row.status)
    }

    @Test
    fun observeAllSessions_ordersByCreatedAtDescending() = runTest {
        val id1 = repo.createSession(title = "first", language = "en")
        // Make sure createdAt differs — System.currentTimeMillis()
        // has only millisecond precision, so a short sleep between
        // inserts is enough.
        Thread.sleep(5)
        val id2 = repo.createSession(title = "second", language = "en")
        Thread.sleep(5)
        val id3 = repo.createSession(title = "third", language = "en")

        val all = repo.observeAllSessions().first()
        assertEquals(listOf(id3, id2, id1), all.map { it.id })
    }

    @Test
    fun renameSpeaker_trimsLabel() = runTest {
        val id = repo.createSession(title = "trim", language = "en")
        val segId = repo.addSegment(id, startMs = 0, endMs = 1_000, text = "x")
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segId to 0),
            speakerCount = 2
        )

        // Surrounding whitespace must not survive the rename —
        // "  Alice " should land as "Alice" in the row.
        repo.renameSpeaker(sessionId = id, speakerIndex = 0, label = "  Alice ")
        assertEquals("Alice", repo.getSpeakers(id)[0].label)

        // The other speaker is untouched.
        assertEquals("Speaker 2", repo.getSpeakers(id)[1].label)
    }

    @Test
    fun renameSpeaker_blankLabelIsIgnored() = runTest {
        val id = repo.createSession(title = "blank", language = "en")
        val segId = repo.addSegment(id, startMs = 0, endMs = 1_000, text = "x")
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segId to 0),
            speakerCount = 1
        )

        // A blank (or whitespace-only) label must not overwrite
        // the existing one — otherwise the user could
        // accidentally blank out a speaker by hitting Save with
        // an empty field.
        repo.renameSpeaker(sessionId = id, speakerIndex = 0, label = "")
        assertEquals("Speaker 1", repo.getSpeakers(id)[0].label)

        repo.renameSpeaker(sessionId = id, speakerIndex = 0, label = "   ")
        assertEquals("Speaker 1", repo.getSpeakers(id)[0].label)
    }

    @Test
    fun renameSpeaker_unknownIndexIsNoOp() = runTest {
        val id = repo.createSession(title = "noop", language = "en")
        // No diarization — there is no speaker row 5 to rename.
        repo.renameSpeaker(sessionId = id, speakerIndex = 5, label = "Bob")
        assertTrue(repo.getSpeakers(id).isEmpty())
    }

    @Test
    fun renameSession_trimsAndTruncates() = runTest {
        val id = repo.createSession(title = "initial", language = "en")

        // Trim: surrounding whitespace must not survive.
        repo.renameSession(sessionId = id, title = "  Standup  ")
        assertEquals("Standup", repo.getSession(id)!!.title)

        // Truncate: a 200-character title is capped at the
        // repository's SESSION_TITLE_MAX_LEN (100). The original
        // substring is preserved so the visible prefix is
        // meaningful — truncation is a hard slice, not a "…".
        val longTitle = "a".repeat(200)
        repo.renameSession(sessionId = id, title = longTitle)
        val stored = repo.getSession(id)!!.title
        assertEquals(SessionRepository.SESSION_TITLE_MAX_LEN, stored.length)
        assertEquals(longTitle.substring(0, SessionRepository.SESSION_TITLE_MAX_LEN), stored)
    }

    @Test
    fun renameSession_blankTitleIsIgnored() = runTest {
        val id = repo.createSession(title = "keep", language = "en")
        repo.renameSession(sessionId = id, title = "")
        assertEquals("keep", repo.getSession(id)!!.title)

        repo.renameSession(sessionId = id, title = "   ")
        assertEquals("keep", repo.getSession(id)!!.title)
    }

    @Test
    fun createSession_storesSpeakerCountAndParticipants() = runTest {
        val id = repo.createSession(
            title = "Interview",
            language = "fr",
            numSpeakers = 2,
            participants = listOf(" Alice ", "Bob", "Alice")
        )
        val row = repo.getSession(id)!!
        assertEquals("fr", row.language)
        assertEquals(2, row.numSpeakers)
        assertEquals(listOf("Alice", "Bob"), ParticipantsCodec.decode(row.participants))
        assertNull(row.asrEngine)

        val anyone = repo.createSession(title = "Any", language = "en", numSpeakers = 0, participants = emptyList())
        assertEquals(-1, repo.getSession(anyone)!!.numSpeakers)
        assertNull(repo.getSession(anyone)!!.participants)
    }

    @Test
    fun setParticipants_replacesAndClears() = runTest {
        val id = repo.createSession(title = "t", language = "en")
        repo.setParticipants(id, listOf("Carol"))
        assertEquals(listOf("Carol"), ParticipantsCodec.decode(repo.getSession(id)!!.participants))
        repo.setParticipants(id, null)
        assertNull(repo.getSession(id)!!.participants)
    }

    @Test
    fun recordEngine_keepsBothWhenAContinuationDiffers() = runTest {
        val id = repo.createSession(title = "t", language = "en")
        repo.recordEngine(id, engine = "npu", model = "asr-npu")
        repo.recordEngine(id, engine = "npu", model = "asr-npu", continued = true)
        assertEquals("npu", repo.getSession(id)!!.asrEngine)
        repo.recordEngine(id, engine = "cpu", model = "asr-cpu", continued = true)
        val row = repo.getSession(id)!!
        assertEquals("npu + cpu", row.asrEngine)
        assertEquals("asr-npu + asr-cpu", row.asrModel)
    }

    @Test
    fun recordEngine_continuingAnOldSessionMarksTheFirstPartUnknown() = runTest {
        val id = repo.createSession(title = "old", language = "en")
        repo.recordEngine(id, engine = "cpu", model = "asr-cpu", continued = true)
        assertEquals("unknown + cpu", repo.getSession(id)!!.asrEngine)
    }

    @Test
    fun replaceTranscript_swapsSegmentsLanguageAndEngine() = runTest {
        val id = repo.createSession(title = "t", language = "en")
        repo.addSegment(id, startMs = 0, endMs = 1000, text = "hello")
        repo.applyDiarization(id, assignments = emptyMap(), speakerCount = 1, labels = mapOf(0 to "Alice"))

        repo.replaceTranscript(
            sessionId = id,
            language = "fr",
            segments = listOf(
                SegmentPiece(startMs = 0, endMs = 600, text = "bonjour", tokens = emptyList(), speakerIndex = 0),
                SegmentPiece(startMs = 600, endMs = 1000, text = "salut", tokens = emptyList(), speakerIndex = null)
            ),
            engine = "cpu",
            model = "asr-fr"
        )

        val row = repo.getSession(id)!!
        assertEquals("fr", row.language)
        assertEquals("cpu", row.asrEngine)
        assertEquals("asr-fr", row.asrModel)
        assertEquals(SessionStatus.TRANSCRIBED, row.status)
        assertEquals(listOf("bonjour", "salut"), repo.getSegments(id).map { it.text })
        assertEquals(listOf(0, null), repo.getSegments(id).map { it.speakerIndex })
        // Speaker rows survive so the next diarization can carry "Alice" over.
        assertEquals(listOf("Alice"), repo.getSpeakers(id).map { it.label })
    }

    @Test
    fun createImportedSession_insertsTranscribedRowWithSegmentsAndMovesTheAudio() = runTest {
        val audio = File(context.cacheDir, "import-${System.nanoTime()}.wav").apply { writeBytes(ByteArray(64)) }
        val id = repo.createImportedSession(
            title = "Voice note",
            language = "fr",
            numSpeakers = 0,
            participants = listOf("Alice"),
            durationMs = 2_000,
            engine = "cpu",
            model = "asr-fr",
            segments = listOf(
                SegmentPiece(0, 1_000, "un", emptyList(), speakerIndex = null),
                SegmentPiece(1_000, 2_000, "deux", emptyList(), speakerIndex = null)
            ),
            audio = audio
        )

        val row = repo.getSession(id)!!
        assertEquals("Voice note", row.title)
        assertEquals(SessionStatus.TRANSCRIBED, row.status)
        assertEquals(2_000L, row.durationMs)
        assertEquals(-1, row.numSpeakers)
        assertEquals("cpu", row.asrEngine)
        assertEquals("asr-fr", row.asrModel)
        assertEquals(File(recordingsDir, "$id.wav").absolutePath, row.audioPath)
        assertTrue(File(row.audioPath).exists())
        assertFalse(audio.exists())
        assertEquals(listOf("un", "deux"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun createImportedSession_rollsBackWhenTheAudioCannotBeMoved() = runTest {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.wav")
        try {
            repo.createImportedSession("t", "en", null, null, 0, "cpu", "m", emptyList(), missing)
            org.junit.Assert.fail("expected the move to fail")
        } catch (_: Exception) {
        }
        assertTrue(repo.observeAllSessions().first().isEmpty())
    }

    // --- Hand edits (issue #26) ---

    private suspend fun threeLines(): Pair<Long, List<Long>> {
        val id = repo.createSession(title = "Edit", language = "en")
        val ids = listOf(
            repo.addSegment(id, 0, 1_000, "the budget", listOf(TimedToken(" the", 0), TimedToken(" budget", 400))),
            repo.addSegment(
                id,
                1_000,
                2_000,
                "for march",
                listOf(TimedToken(" for", 1_000), TimedToken(" march", 1_500))
            ),
            repo.addSegment(id, 2_000, 3_000, "okay", emptyList())
        )
        repo.applyDiarization(id, mapOf(ids[0] to 0, ids[1] to 1, ids[2] to 0), speakerCount = 2)
        return id to ids
    }

    @Test
    fun editLineText_persistsAndIsSearchable_andUndoRestores() = runTest {
        val (id, ids) = threeLines()
        val undo = repo.editLineText(ids[1], "for April")!!

        val edited = repo.getSegments(id)[1]
        assertEquals("for April", edited.text)
        assertTrue(edited.textEdited)
        assertEquals(listOf(1_000L, 1_500L), TokenTimingCodec.decode(edited.tokens).map { it.ms })
        assertTrue(repo.getSession(id)!!.transcriptEdited)
        val hits = db.searchDao().findSegments("april*", null, 0L, "[", "]", 10)
        assertEquals(listOf(ids[1]), hits.map { it.segmentId })

        repo.undo(undo)
        assertEquals("for march", repo.getSegments(id)[1].text)
        assertFalse(repo.getSegments(id)[1].textEdited)
        assertFalse(repo.getSession(id)!!.transcriptEdited)
        assertTrue(db.searchDao().findSegments("april*", null, 0L, "[", "]", 10).isEmpty())
    }

    @Test
    fun editLineText_unchanged_writesNothing() = runTest {
        val (id, ids) = threeLines()
        assertNull(repo.editLineText(ids[0], " the budget "))
        assertFalse(repo.getSession(id)!!.transcriptEdited)
    }

    @Test
    fun setLineSpeaker_existingAndNew_andUndoRemovesTheNewSpeaker() = runTest {
        val (id, ids) = threeLines()
        repo.setLineSpeaker(ids[2], 1)
        assertEquals(1, repo.getSegments(id)[2].speakerIndex)
        assertTrue(repo.getSegments(id)[2].speakerEdited)

        val undo = repo.setLineNewSpeaker(ids[0], "  Dana ")!!
        assertEquals(2, repo.getSegments(id)[0].speakerIndex)
        assertEquals("Dana", repo.getSpeakers(id).last().label)

        repo.undo(undo)
        assertEquals(0, repo.getSegments(id)[0].speakerIndex)
        assertEquals(listOf(0, 1), repo.getSpeakers(id).map { it.speakerIndex })
        assertTrue("the first speaker change still stands", repo.getSession(id)!!.transcriptEdited)
    }

    @Test
    fun mergeWithNext_joinsLines_andUndoBringsTheSecondBack() = runTest {
        val (id, ids) = threeLines()
        val undo = repo.mergeWithNext(ids[0])!!
        val lines = repo.getSegments(id)
        assertEquals(listOf("the budget for march", "okay"), lines.map { it.text })
        assertEquals(2_000L, lines[0].endMs)
        assertNull(repo.mergeWithNext(ids[2]))

        repo.undo(undo)
        assertEquals(ids, repo.getSegments(id).map { it.id })
        assertEquals(listOf("the budget", "for march", "okay"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun splitLine_insertsTheSecondPart_andUndoRemovesIt() = runTest {
        val (id, ids) = threeLines()
        val undo = repo.splitLine(ids[1], 3)!!
        val lines = repo.getSegments(id)
        assertEquals(listOf("the budget", "for", "march", "okay"), lines.map { it.text })
        assertEquals(1_500L, lines[2].startMs)
        assertEquals(1, lines[2].speakerIndex)

        repo.undo(undo)
        assertEquals(listOf("the budget", "for march", "okay"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun deleteLine_andUndo() = runTest {
        val (id, ids) = threeLines()
        val undo = repo.deleteLine(ids[1])!!
        assertEquals(listOf("the budget", "okay"), repo.getSegments(id).map { it.text })
        assertTrue(repo.getSession(id)!!.transcriptEdited)

        repo.undo(undo)
        assertEquals(listOf("the budget", "for march", "okay"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun replaceTranscript_clearsTheEditedFlag() = runTest {
        val (id, ids) = threeLines()
        repo.deleteLine(ids[2])
        repo.replaceTranscript(id, "fr", listOf(SegmentPiece(0, 1_000, "bonjour", emptyList(), null)), "cpu", "m")
        assertFalse(repo.getSession(id)!!.transcriptEdited)
    }
}
