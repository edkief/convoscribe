package com.convoscribe.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.llm.ExtractedActionItem
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionExportLoaderTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db, File(context.filesDir, "recordings-${System.nanoTime()}").apply { mkdirs() })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun load_readsRenamesTagsItemsAndQuestionPairs() = runTest {
        val id = repo.createSession(title = "Réunion", language = "fr")
        val line = repo.addSegment(id, startMs = 0L, endMs = 1_000L, text = "Bonjour.", tokens = emptyList())
        repo.applyDiarization(id, assignments = mapOf(line to 0), speakerCount = 1)
        repo.renameSpeaker(id, 0, "Élodie")
        repo.setParticipants(id, listOf("Élodie", "Marc"))
        repo.setSessionTags(id, listOf("équipe"))
        repo.addMark(id, atMs = 500L, note = "ici")
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "Old.")
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "Résumé.", templateName = "Réunion")
        repo.replaceExtractedItems(id, listOf(ExtractedActionItem("Keep"), ExtractedActionItem("Drop")))
        repo.deleteActionItem(repo.observeActionItems(id).first().last().id)
        repo.addTalkingPoint(id, "Budget")
        repo.addMessage(id, MessageKind.QA, MessageRole.ASSISTANT, "Orphan answer.")
        repo.addMessage(id, MessageKind.QA, MessageRole.USER, "Combien ?")
        repo.addMessage(id, MessageKind.QA, MessageRole.ASSISTANT, "Dix.")
        repo.addMessage(id, MessageKind.QA, MessageRole.USER, "Quand ?")

        val session = SessionExportLoader(repo) { ZoneId.of("Europe/Paris") }.load(id)!!

        assertEquals("Réunion", session.title)
        assertEquals(listOf(ExportSpeaker(0, "Élodie")), session.speakers)
        assertEquals("Élodie", session.lines.single().speaker)
        assertEquals(listOf("Élodie", "Marc"), session.participants)
        assertEquals(listOf("équipe"), session.tags)
        assertEquals("ici", session.marks.single().note)
        assertEquals("Résumé.", session.summary!!.content)
        assertEquals("Réunion", session.summary!!.templateName)
        assertEquals(listOf("Keep"), session.actionItems.map { it.text })
        assertEquals(listOf("Budget"), session.talkingPoints.map { it.text })
        assertEquals(
            listOf("Combien ?" to "Dix.", "Quand ?" to null),
            session.questions.map { it.question to it.answer }
        )
    }

    @Test
    fun load_missingSession_isNull() = runTest {
        assertNull(SessionExportLoader(repo).load(404L))
    }
}
