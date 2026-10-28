package com.convoscribe.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.TimedToken
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SessionSearch] against a real in-memory Room database, so the FTS4
 * index, its sync triggers and the `unicode61` folding are exercised
 * through the same repository calls the app makes.
 */
@RunWith(RobolectricTestRunner::class)
class SessionSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var search: SessionSearch

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "recordings-search-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir)
        search = SessionSearch(db.searchDao())
    }

    @After
    fun tearDown() {
        db.close()
        recordingsDir.deleteRecursively()
    }

    private suspend fun session(
        title: String,
        language: String = "en",
        createdAt: Long = 1_000L,
        participants: List<String>? = null
    ): Long = db.sessionDao().insert(
        SessionEntity(
            title = title,
            createdAt = createdAt,
            language = language,
            status = SessionStatus.DIARIZED,
            participants = ParticipantsCodec.encode(participants)
        )
    )

    @Test
    fun frenchAccentsAndCase_matchEitherWay() = runTest {
        val id = session("Lundi", language = "fr")
        val line = repo.addSegment(id, startMs = 4_000, endMs = 6_000, text = "La réunion sur le BUDGET commence")

        for (query in listOf("reunion", "RÉUNION", "Réun", "budget reunion")) {
            val result = search.search(query).single()
            assertEquals(query, listOf(line), result.lines.map { it.segmentId })
            assertEquals(4_000L, result.lines.single().startMs)
        }
        val highlighted = search.search("reunion").single().lines.single().text
        assertEquals(listOf("réunion"), highlighted.ranges.map { highlighted.text.substring(it) })
    }

    @Test
    fun englishCaseAndPrefix_andEveryWordMustMatch() = runTest {
        val id = session("Standup")
        repo.addSegment(id, startMs = 0, endMs = 1_000, text = "Quarterly Budget review")
        repo.addSegment(id, startMs = 1_000, endMs = 2_000, text = "Lunch plans")

        assertEquals(1, search.search("budg").single().lines.size)
        assertEquals(1, search.search("QUARTERLY review").single().lines.size)
        assertEquals(emptyList<SearchResult>(), search.search("budget lunch"))
        assertEquals(emptyList<SearchResult>(), search.search("udget"))
        assertEquals(emptyList<SearchResult>(), search.search("  "))
    }

    @Test
    fun titlesSpeakersAndSummaries_match() = runTest {
        val titled = session("Préparation du budget", createdAt = 3_000)
        val spoken = session("Weekly", createdAt = 2_000)
        db.speakerDao().upsert(listOf(SpeakerEntity(spoken, 0, "Élodie"), SpeakerEntity(spoken, 1, "Speaker 2")))
        val summarised = session("Call", createdAt = 1_000)
        repo.addMessage(summarised, MessageKind.SUMMARY, MessageRole.ASSISTANT, "Old summary")
        repo.addMessage(summarised, MessageKind.SUMMARY, MessageRole.ASSISTANT, "We agreed the new budget.")

        val budget = search.search("budget")
        assertEquals(listOf(titled, summarised), budget.map { it.session.id })
        assertEquals(listOf(15..20), budget[0].title.ranges)
        assertEquals("We agreed the new budget.", budget[1].summary?.text)

        val elodie = search.search("elodie").single()
        assertEquals(spoken, elodie.session.id)
        assertEquals(listOf("Élodie"), elodie.speakers.map { it.text })
        assertNull(elodie.summary)
        assertEquals(emptyList<SearchResult>(), search.search("old summary"))
    }

    @Test
    fun linesCarryTheirSpeakerLabel_andGroupNewestSessionFirst() = runTest {
        val older = session("Older", createdAt = 1_000)
        val newer = session("Newer", createdAt = 2_000)
        val a = repo.addSegment(older, startMs = 5_000, endMs = 6_000, text = "budget later")
        val b = repo.addSegment(older, startMs = 1_000, endMs = 2_000, text = "budget first")
        repo.addSegment(newer, startMs = 0, endMs = 1_000, text = "budget again")
        repo.applyDiarization(older, mapOf(a to 0, b to null), speakerCount = 1, labels = mapOf(0 to "Alice"))

        val results = search.search("budget")
        assertEquals(listOf(newer, older), results.map { it.session.id })
        assertEquals(listOf(b, a), results[1].lines.map { it.segmentId })
        assertEquals(listOf(null, "Alice"), results[1].lines.map { it.speaker })
    }

    @Test
    fun index_followsReTranscriptionSplitsAndDeletes() = runTest {
        val id = session("Sync")
        val original = repo.addSegment(id, startMs = 0, endMs = 4_000, text = "alpha beta")
        assertEquals(listOf(original), search.search("alpha").single().lines.map { it.segmentId })

        // Diarization splits the line at a speaker change.
        repo.applyDiarization(
            sessionId = id,
            assignments = emptyMap(),
            speakerCount = 2,
            replacements = mapOf(
                original to listOf(
                    SegmentPiece(0, 2_000, "alpha", listOf(TimedToken(" alpha", 0)), 0),
                    SegmentPiece(2_000, 4_000, "beta", listOf(TimedToken(" beta", 2_000)), 1)
                )
            )
        )
        val beta = search.search("beta").single().lines.single()
        assertEquals(2_000L, beta.startMs)
        assertTrue(beta.segmentId != original)
        assertEquals(emptyList<SearchResult>(), search.search("alpha beta"))

        // Re-transcription replaces every line.
        repo.replaceTranscript(
            sessionId = id,
            language = "fr",
            segments = listOf(SegmentPiece(0, 3_000, "gamma déjà", emptyList(), null)),
            engine = "cpu",
            model = "m"
        )
        assertEquals(emptyList<SearchResult>(), search.search("beta"))
        val gamma = search.search("deja").single().lines.single()
        db.segmentDao().delete(gamma.segmentId)
        assertEquals(emptyList<SearchResult>(), search.search("gamma"))

        repo.addSegment(id, startMs = 0, endMs = 1_000, text = "delta")
        repo.deleteSession(id)
        assertEquals(emptyList<SearchResult>(), search.search("delta"))
        assertEquals(0, db.searchDao().findSegments("delta*", null, 0L, "[", "]", 10).size)
    }

    @Test
    fun filters_languageDateAndSpeaker() = runTest {
        val fr = session("budget fr", language = "fr", createdAt = 1_000, participants = listOf("Zoé"))
        val en = session("budget en", language = "en", createdAt = 5_000)
        db.speakerDao().upsert(listOf(SpeakerEntity(en, 0, "Bob")))
        repo.addSegment(fr, startMs = 0, endMs = 1_000, text = "le budget")
        repo.addSegment(en, startMs = 0, endMs = 1_000, text = "the budget")

        fun ids(results: List<SearchResult>) = results.map { it.session.id }
        assertEquals(listOf(en, fr), ids(search.search("budget")))
        assertEquals(listOf(fr), ids(search.search("budget", SearchFilters(language = "fr"))))
        assertEquals(listOf(en), ids(search.search("budget", SearchFilters(since = 2_000))))
        assertEquals(listOf(en), ids(search.search("budget", SearchFilters(speaker = "BOB"))))
        assertEquals(listOf(fr), ids(search.search("budget", SearchFilters(speaker = "zoe"))))
        assertEquals(emptyList<Long>(), ids(search.search("budget", SearchFilters(language = "fr", speaker = "Bob"))))
    }

    @Test
    fun options_listLanguagesAndNamedPeople() = runTest {
        val a = session("a", language = "fr", createdAt = 2_000, participants = listOf("Zoé", "bob"))
        val b = session("b", language = "en", createdAt = 1_000)
        db.speakerDao().upsert(listOf(SpeakerEntity(a, 0, "Bob"), SpeakerEntity(b, 0, "Speaker 1")))

        val options = search.options()
        assertEquals(listOf("fr", "en"), options.languages)
        assertEquals(listOf("Bob", "Zoé"), options.speakers)
    }

    @Test
    fun tagFilter_keepsSessionsWithAnyOfTheTags_andOptionsListUsedTags() = runTest {
        val acme = session("Budget Acme", createdAt = 3_000)
        val other = session("Budget other", createdAt = 2_000)
        val weekly = session("Budget weekly", createdAt = 1_000)
        repo.setSessionTags(acme, listOf("Acme"))
        repo.setSessionTags(weekly, listOf("Weekly"))
        repo.setSessionTags(other, listOf("Unused"))
        repo.setSessionTags(other, emptyList())
        val tags = search.options().tags
        assertEquals(listOf("Acme", "Weekly"), tags.map { it.name })

        val ids = tags.mapTo(HashSet()) { it.id }
        assertEquals(listOf(acme, weekly), search.search("budget", SearchFilters(tagIds = ids)).map { it.session.id })
        val acmeOnly = SearchFilters(tagIds = setOf(tags.first().id))
        assertEquals(listOf(acme), search.search("budget", acmeOnly).map { it.session.id })
        assertEquals(3, search.search("budget").size)
    }

    @Test
    fun candidates_rankByMatchingLines_ignoreStopWords_andCarrySummaries() = runTest {
        val one = session("One", createdAt = 3_000L)
        val two = session("Two", createdAt = 2_000L)
        val none = session("None", createdAt = 1_000L)
        repo.addSegment(one, startMs = 0, endMs = 1_000, text = "The budget was discussed")
        repo.addSegment(two, startMs = 0, endMs = 1_000, text = "Budget first")
        repo.addSegment(two, startMs = 1_000, endMs = 2_000, text = "and the budget again")
        repo.addSegment(none, startMs = 0, endMs = 1_000, text = "What was the weather like")
        repo.addMessage(two, MessageKind.SUMMARY, MessageRole.ASSISTANT, "Budget talks.")

        val result = search.candidates("What did they say about the budget?")
        assertEquals(listOf(two, one), result.map { it.session.id })
        assertEquals(listOf(2, 1), result.map { it.hits })
        assertEquals("Budget talks.", result.first().summary)
        assertNull(result.last().summary)
    }

    @Test
    fun candidates_respectFiltersAndLimit() = runTest {
        val en = session("En", createdAt = 5_000L)
        val fr = session("Fr", language = "fr", createdAt = 5_000L)
        val old = session("Old", createdAt = 100L)
        val bob = session("Bob's", createdAt = 4_000L)
        val tagged = session("Tagged", createdAt = 4_000L)
        for (id in listOf(en, fr, old, bob, tagged)) repo.addSegment(id, startMs = 0, endMs = 1_000, text = "budget")
        db.speakerDao().upsert(listOf(SpeakerEntity(bob, 0, "Bob")))
        repo.setSessionTags(tagged, listOf("Acme"))

        suspend fun ids(filters: SearchFilters) = search.candidates("budget", filters).map { it.session.id }
        assertEquals(listOf(fr), ids(SearchFilters(language = "fr")))
        assertTrue(old !in ids(SearchFilters(since = 1_000L)))
        assertEquals(listOf(bob), ids(SearchFilters(speaker = "bob")))
        val tag = search.options().tags.single().id
        assertEquals(listOf(tagged), ids(SearchFilters(tagIds = setOf(tag))))
        assertEquals(2, search.candidates("budget", limit = 2).size)
    }

    @Test
    fun candidates_withoutAnyMatch_fallBackToTheNewestSessions() = runTest {
        val ids = (1..7).map { session("S$it", createdAt = it * 1_000L) }
        ids.forEach { repo.addSegment(it, startMs = 0, endMs = 1_000, text = "weather") }

        val result = search.candidates("What was decided?")
        assertEquals(ids.reversed().take(SessionSearch.FALLBACK_SESSIONS), result.map { it.session.id })
        assertTrue(result.all { it.hits == 0 })
    }
}
