package com.convoscribe.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.MAX_SAMPLES_PER_PERSON
import com.convoscribe.data.Preset
import com.convoscribe.data.PresetRepository
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SummaryTemplateRepository
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Known voices, presets and summary templates syncing between two
 * devices through one [FakeSyncServer] (issue #70).
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {
    private lateinit var context: Context
    private lateinit var fake: FakeSyncServer
    private lateinit var root: File
    private lateinit var a: Device
    private lateinit var b: Device
    private var now = 1_000L

    inner class Device(name: String) {
        val db: AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dir = File(root, name).apply { mkdirs() }
        val repo = SessionRepository(db, File(dir, "recordings").apply { mkdirs() }) { now }
        val templates = SummaryTemplateRepository(db.summaryTemplateDao()) { now }
        val presets = PresetRepository(db) { now }
        val settings = SettingsRepository(
            context.getSharedPreferences("library-$name-${System.nanoTime()}", 0),
            InMemorySecretStore()
        )
        val engine = SyncEngine(
            db = db,
            repository = repo,
            settings = settings,
            http = OkHttpClient(),
            status = SyncStatusStore(context.getSharedPreferences("library-status-$name-${System.nanoTime()}", 0)),
            isRecording = { false },
            downloadDir = File(dir, "downloads"),
            clock = { now }
        )

        init {
            settings.update { it.copy(syncServerUrl = fake.url, syncEnabled = true) }
            settings.setSyncKey(fake.apiKey)
        }

        suspend fun sync(): SyncOutcome = engine.run().also { now += 10 }

        suspend fun addVoice(name: String, sample: Byte, createdAt: Long) {
            val dao = db.enrolledSpeakerDao()
            dao.insert(EnrolledSpeakerEntity(name = name, embedding = byteArrayOf(sample), createdAt = createdAt))
            dao.pruneName(name, MAX_SAMPLES_PER_PERSON)
        }

        suspend fun voiceIds(): Set<String> = db.enrolledSpeakerDao().getAll().map { it.syncId }.toSet()

        suspend fun templateBodies(): List<String> = templates.getUserTemplates().map { "${it.name}: ${it.body}" }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "library-${System.nanoTime()}").apply { mkdirs() }
        fake = FakeSyncServer()
        fake.server.start()
        a = Device("a")
        b = Device("b")
    }

    @After
    fun tearDown() {
        fake.shutdown()
        a.db.close()
        b.db.close()
        root.deleteRecursively()
    }

    @Test
    fun theLibraryReachesTheOtherDevice() = runTest {
        val weekly = a.templates.add("Weekly", "Decisions first")
        a.presets.save(
            Preset(
                name = "Standup",
                language = "en",
                tags = listOf("Acme"),
                summaryTemplate = weekly,
                cleanAudio = true
            )
        )
        a.addVoice("Ann", 1, createdAt = 10)

        assertEquals(SyncOutcome.DONE, a.sync())
        assertEquals(SyncOutcome.DONE, b.sync())

        val template = b.db.summaryTemplateDao().getAll().single()
        assertEquals("Decisions first", template.body)
        assertEquals(a.db.summaryTemplateDao().getAll().single().syncId, template.syncId)
        val preset = b.presets.observeAll().first().single()
        assertEquals(listOf("Acme"), preset.tags)
        assertEquals(SummaryTemplates.userKey(template.id), preset.summaryTemplate)
        assertTrue(preset.cleanAudio)
        val voice = b.db.enrolledSpeakerDao().getAll().single()
        assertEquals("Ann", voice.name)
        assertEquals(listOf<Byte>(1), voice.embedding.toList())
        assertEquals(a.voiceIds(), b.voiceIds())

        // Nothing changed, so nothing goes up again.
        fake.requests.clear()
        a.sync()
        b.sync()
        assertTrue(fake.requests.none { it.startsWith("PUT documents/") })
    }

    @Test
    fun editsAndDeletesTravelBothWays() = runTest {
        val weekly = a.templates.add("Weekly", "Decisions first")
        val presetId = a.presets.save(Preset(name = "Standup", language = "en", summaryTemplate = weekly))
        a.addVoice("Ann", 1, createdAt = 10)
        a.sync()
        b.sync()

        val bWeekly = SummaryTemplates.userKey(b.db.summaryTemplateDao().getAll().single().id)
        b.templates.update(bWeekly, "Weekly sync", "Actions first")
        b.db.enrolledSpeakerDao().deleteByName("Ann")
        b.sync()
        a.presets.delete(presetId)
        a.sync()
        b.sync()

        assertEquals(listOf("Weekly sync: Actions first"), a.templateBodies())
        assertEquals(emptySet<String>(), a.voiceIds())
        assertEquals(emptyList<Preset>(), b.presets.observeAll().first())
    }

    @Test
    fun theMostRecentEditWins() = runTest {
        a.templates.add("Weekly", "v1")
        a.sync()
        b.sync()
        val key = { d: Device -> suspend { SummaryTemplates.userKey(d.db.summaryTemplateDao().getAll().single().id) } }

        a.templates.update(key(a)(), "Weekly", "from A")
        now += 100
        b.templates.update(key(b)(), "Weekly", "from B, later")
        a.sync()
        b.sync()
        a.sync()

        assertEquals(listOf("Weekly: from B, later"), a.templateBodies())
        assertEquals(listOf("Weekly: from B, later"), b.templateBodies())
    }

    @Test
    fun identicalItemsOnBothDevicesMergeOnFirstSync() = runTest {
        for (device in listOf(a, b)) {
            device.templates.add("Weekly", "Decisions first")
            device.presets.save(
                Preset(name = "Standup", language = "en", tags = listOf("Acme"), summaryTemplate = "meeting")
            )
            device.addVoice("Ann", 1, createdAt = 10)
        }

        a.sync()
        b.sync()
        a.sync()

        for (device in listOf(a, b)) {
            assertEquals(1, device.db.summaryTemplateDao().getAll().size)
            assertEquals(1, device.presets.observeAll().first().size)
            assertEquals(1, device.db.enrolledSpeakerDao().getAll().size)
        }
        assertEquals(a.voiceIds(), b.voiceIds())
        assertEquals(1, fake.docs("template").size)
        assertEquals(1, fake.docs("preset").size)
    }

    @Test
    fun voiceSamplesFromBothDevicesSettleOnTheSameNewest() = runTest {
        for (i in 1..MAX_SAMPLES_PER_PERSON) a.addVoice("Ann", i.toByte(), createdAt = i * 10L)
        a.sync()
        b.sync()

        a.addVoice("Ann", 100, createdAt = 1000)
        b.addVoice("Ann", 101, createdAt = 1001)
        repeat(3) {
            a.sync()
            b.sync()
        }

        assertEquals(MAX_SAMPLES_PER_PERSON, a.voiceIds().size)
        assertEquals(a.voiceIds(), b.voiceIds())
        val kept = a.db.enrolledSpeakerDao().getAll().map { it.createdAt }.sorted()
        assertEquals(listOf(30L, 40L, 50L, 1000L, 1001L), kept)
    }

    @Test
    fun aServerOlderThanTheLibraryStillSyncsSessions() = runTest {
        fake.kinds = setOf("session")
        a.templates.add("Weekly", "Decisions first")
        val id = a.repo.createSession(title = "Standup", language = "en")
        a.repo.finishRecording(id, durationMs = 1_000)

        assertEquals(SyncOutcome.DONE, a.sync())

        assertNotNull(fake.documents[a.repo.getSession(id)!!.syncId])
    }
}
