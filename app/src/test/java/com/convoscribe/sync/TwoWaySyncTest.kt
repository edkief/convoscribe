package com.convoscribe.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.PresetEntity
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.SyncKinds
import com.convoscribe.data.cleanAudioFile
import com.convoscribe.llm.ExtractedActionItem
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
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
 * Two devices syncing through one [FakeSyncServer] (issue #69):
 * downloads, edits both ways, merging (#90), deletes everywhere and
 * *Remove from this device*.
 */
@RunWith(RobolectricTestRunner::class)
class TwoWaySyncTest {
    private lateinit var context: Context
    private lateinit var fake: FakeSyncServer
    private lateinit var root: File
    private lateinit var a: Device
    private lateinit var b: Device
    private var now = 1_000L

    /** One install: its own database, recordings, settings and sync engine. */
    inner class Device(name: String) {
        val db: AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dir = File(root, name).apply { mkdirs() }
        val repo = SessionRepository(db, File(dir, "recordings").apply { mkdirs() }) { now }
        val settings = SettingsRepository(
            context.getSharedPreferences("two-way-$name-${System.nanoTime()}", 0),
            InMemorySecretStore()
        )
        val status = SyncStatusStore(context.getSharedPreferences("two-way-status-$name-${System.nanoTime()}", 0))
        var metered = false
        var attached = 0
        val engine = SyncEngine(
            db = db,
            repository = repo,
            settings = settings,
            http = OkHttpClient(),
            status = status,
            isRecording = { false },
            downloadDir = File(dir, "downloads"),
            isMetered = { metered },
            onRecordingAttached = { attached++ },
            clock = { now },
            partBytes = 4
        )

        init {
            settings.update { it.copy(syncServerUrl = fake.url, syncEnabled = true) }
            settings.setSyncKey(fake.apiKey)
        }

        suspend fun sync(): SyncOutcome = engine.run().also { now += 10 }

        suspend fun session(syncId: String) = db.sessionDao().getBySyncId(syncId)

        suspend fun hash(syncId: String): String {
            val row = session(syncId)!!
            return SessionManifests.hash(repo.snapshot(row.id)!!.manifest)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "two-way-${System.nanoTime()}").apply { mkdirs() }
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

    /**
     * A finished session on [device] with a recording, a cleaned copy,
     * a named speaker, a summary, a mark and a tag.
     */
    private suspend fun fullSession(device: Device, durationMs: Long = 1_000): String {
        val repo = device.repo
        val id = repo.createSession(title = "Standup", language = "en", tags = listOf("work"))
        val row = repo.getSession(id)!!
        File(row.audioPath).writeBytes(AUDIO)
        repo.addSegment(id, startMs = 0, endMs = 500, text = "hello")
        repo.addMark(id, atMs = 100, note = "decision")
        repo.finishRecording(id, durationMs = durationMs)
        device.db.sessionDao().setCleanedWith(id, "cleaner")
        row.cleanAudioFile().writeBytes(CLEAN)
        repo.applyDiarization(id, mapOf(repo.getSegments(id).single().id to 0), speakerCount = 1)
        repo.renameSpeaker(id, 0, "Ann")
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "the summary")
        return row.syncId
    }

    @Test
    fun aSessionFromAnotherDeviceArrivesComplete() = runTest {
        val syncId = fullSession(a)
        assertEquals(SyncOutcome.DONE, a.sync())

        assertEquals(SyncOutcome.DONE, b.sync())

        val row = b.session(syncId)!!
        assertEquals(a.hash(syncId), b.hash(syncId))
        assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
        assertEquals(CLEAN.toList(), row.cleanAudioFile().readBytes().toList())
        assertEquals(listOf(SpeakerEntity(row.id, 0, "Ann")), b.repo.getSpeakers(row.id))
        assertEquals(
            listOf("the summary"),
            b.db.chatMessageDao().getForSession(row.id).filter {
                it.kind == MessageKind.SUMMARY
            }.map { it.content }
        )
        assertEquals(listOf("decision"), b.repo.getMarks(row.id).map { it.note })
        assertEquals(1, b.attached)
        assertNull(b.db.syncDao().state(SyncKinds.SESSION, syncId)!!.audioSha)
        assertEquals(0, b.status.status.value.downloading)

        // Nothing changed on either side: no more pushes.
        fake.requests.clear()
        b.sync()
        a.sync()
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents") })
    }

    @Test
    fun editsOnOneDeviceReachTheOther() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        val id = b.session(syncId)!!.id
        b.repo.renameSession(id, "Renamed on B")
        b.repo.editLineText(b.repo.getSegments(id).single().id, "hello there")
        b.repo.renameSpeaker(id, 0, "Bob")
        b.repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "a new summary")

        fake.requests.clear()
        b.sync()
        a.sync()

        val onA = a.session(syncId)!!
        assertEquals("Renamed on B", onA.title)
        assertEquals(listOf("hello there"), a.repo.getSegments(onA.id).map { it.text })
        assertEquals("Bob", a.repo.getSpeakers(onA.id).single().label)
        assertTrue(
            a.db.chatMessageDao().getForSession(onA.id).filter {
                it.kind == MessageKind.SUMMARY
            }.any { it.content == "a new summary" }
        )
        assertEquals(a.hash(syncId), b.hash(syncId))
        // The recordings were already there and were not fetched again.
        assertFalse(fake.requests.any { it.startsWith("GET blobs/") })
    }

    @Test
    fun theLongerRecordingWinsAndTheRenameSurvives() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        a.repo.setDuration(a.session(syncId)!!.id, 5_000)
        now += 100
        b.repo.renameSession(b.session(syncId)!!.id, "Renamed later on B")

        b.sync()
        a.sync()
        b.sync()

        assertEquals(5_000L, b.session(syncId)!!.durationMs)
        assertEquals("Renamed later on B", b.session(syncId)!!.title)
        assertEquals("Renamed later on B", a.session(syncId)!!.title)
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun withEqualRecordingsTheLaterChangeWinsWhicheverSyncsFirst() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.renameSession(b.session(syncId)!!.id, "Earlier on B")
        now += 100
        a.repo.renameSession(a.session(syncId)!!.id, "Later on A")

        // B goes first and is accepted; A's later change still wins.
        b.sync()
        a.sync()
        b.sync()
        assertEquals("Later on A", b.session(syncId)!!.title)
        assertEquals("Later on A", a.session(syncId)!!.title)

        // The losing device takes the winner in the same run as its refused push.
        b.repo.renameSession(b.session(syncId)!!.id, "Old news")
        now += 100
        a.repo.renameSession(a.session(syncId)!!.id, "Newest")
        a.sync()
        b.sync()
        assertEquals("Newest", b.session(syncId)!!.title)
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun aDeleteReachesEveryDevice() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()

        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()
        b.sync()

        assertNull(b.session(syncId))
        assertNull(b.db.syncDao().state(SyncKinds.SESSION, syncId))
        assertEquals(emptyList<Any>(), a.db.syncDao().tombstones())
    }

    @Test
    fun aLongerRecordingSurvivesADeleteFromAnotherDevice() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.setDuration(b.session(syncId)!!.id, 9_000)
        b.sync()

        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()

        assertEquals(9_000L, a.session(syncId)!!.durationMs)
        assertNotNull(fake.documents.getValue(syncId).manifest)
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun anEditDoesNotBringBackASessionDeletedElsewhere() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()

        b.repo.renameSession(b.session(syncId)!!.id, "Too late")
        b.sync()

        assertNull(b.session(syncId))
        assertNull(fake.documents.getValue(syncId).manifest)
    }

    @Test
    fun removeFromThisDeviceKeepsItOnTheServerUntilAskedFor() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()

        b.repo.deleteSession(b.session(syncId)!!.id, everywhere = false)
        b.sync()
        b.sync()
        a.sync()

        assertNull(b.session(syncId))
        assertNotNull(a.session(syncId))
        assertNotNull(fake.documents.getValue(syncId).manifest)
        assertTrue(b.db.syncDao().state(SyncKinds.SESSION, syncId)!!.excluded)

        b.repo.downloadAgain(syncId)
        b.sync()
        assertEquals(AUDIO.toList(), File(b.session(syncId)!!.audioPath).readBytes().toList())
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun aRemovedSessionDeletedElsewhereIsForgotten() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.deleteSession(b.session(syncId)!!.id, everywhere = false)

        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()
        b.sync()

        assertNull(b.db.syncDao().state(SyncKinds.SESSION, syncId))
    }

    @Test
    fun anInterruptedDownloadResumesAndNeverLeavesAHalfSession() = runTest {
        val syncId = fullSession(a)
        a.sync()
        val ranges = mutableListOf<String?>()
        var cut = true
        fake.intercept = { request ->
            if (request.method == "GET" && request.path!!.contains("/blobs/")) ranges += request.getHeader("Range")
            if (cut && request.method == "GET" && request.path!!.endsWith("/blobs/${FakeSyncServer.sha256(AUDIO)}")) {
                cut = false
                MockResponse().setResponseCode(200).setHeader("Content-Length", AUDIO.size)
                    .setBody(Buffer().write(AUDIO)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                null
            }
        }

        assertEquals(SyncOutcome.RETRY, b.sync())
        val row = b.session(syncId)!!
        assertEquals(listOf("hello"), b.repo.getSegments(row.id).map { it.text })
        assertFalse(File(row.audioPath).exists())
        assertNotNull(b.db.syncDao().state(SyncKinds.SESSION, syncId)!!.audioSha)
        assertEquals(1L, fake.documents.getValue(syncId).revision)

        assertEquals(SyncOutcome.DONE, b.sync())
        assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
        assertTrue(ranges.any { it != null && it.startsWith("bytes=") })
    }

    @Test
    fun recordingsWaitForAnUnmeteredNetworkUnlessMobileDataIsAllowed() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.metered = true

        assertEquals(SyncOutcome.AUDIO_WAITING, b.sync())
        val row = b.session(syncId)!!
        assertFalse(File(row.audioPath).exists())
        assertEquals(2, b.status.status.value.downloading)
        // Waiting for its recording is not a change to upload.
        fake.requests.clear()
        b.sync()
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents") })

        b.settings.update { it.copy(syncAudioOnMobileData = true) }
        assertEquals(SyncOutcome.DONE, b.sync())
        assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
    }

    /**
     * The manifest of [syncId] as the web app would rewrite it (#84): keys in
     * another order, a field this app doesn't know, a new title and line text.
     */
    private fun webEdited(syncId: String): String {
        val manifest = Json.parseToJsonElement(fake.documents.getValue(syncId).manifest!!).jsonObject
        val line = manifest.getValue("lines").jsonArray[0].jsonObject
        val edited = line + mapOf(
            "text" to JsonPrimitive("Hello there"),
            "tokens" to JsonArray(emptyList()),
            "textEdited" to JsonPrimitive(true),
            "lineFuture" to JsonPrimitive("kept")
        )
        val changed = manifest + mapOf(
            "title" to JsonPrimitive("Renamed on the web"),
            "titleSource" to JsonPrimitive("user"),
            "transcriptEdited" to JsonPrimitive(true),
            "lines" to JsonArray(listOf(JsonObject(edited))),
            "futureField" to JsonPrimitive("from a newer app")
        )
        return JsonObject(changed.entries.reversed().associate { it.key to it.value }).toString()
    }

    @Test
    fun aWebEditReachesDevicesAndIsNotPushedBack() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        fake.serverWrite(syncId, webEdited(syncId), now)
        val revision = fake.documents.getValue(syncId).revision
        fake.requests.clear()

        assertEquals(SyncOutcome.DONE, a.sync())
        assertEquals(SyncOutcome.DONE, b.sync())
        a.sync()
        b.sync()

        for (device in listOf(a, b)) {
            val row = device.session(syncId)!!
            assertEquals("Renamed on the web", row.title)
            assertTrue(row.transcriptEdited)
            val line = device.repo.getSegments(row.id).single()
            assertEquals("Hello there", line.text)
            assertTrue(line.textEdited)
        }
        assertEquals(a.hash(syncId), b.hash(syncId))
        // No device answers with a push, and the recordings weren't fetched again.
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents") })
        assertFalse(fake.requests.any { it.startsWith("GET blobs/") })
        assertEquals(revision, fake.documents.getValue(syncId).revision)

        // A later edit on a device goes on from the web's revision.
        b.repo.renameSession(b.session(syncId)!!.id, "Renamed on B")
        b.sync()
        a.sync()
        assertEquals(revision + 1, fake.documents.getValue(syncId).revision)
        assertEquals("Renamed on B", a.session(syncId)!!.title)
    }

    @Test
    fun aRestoreFromTheWebBringsADeletedSessionBackEverywhere() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        val kept = fake.documents.getValue(syncId).manifest!!
        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()
        b.sync()
        assertNull(a.session(syncId))
        assertNull(b.session(syncId))

        fake.serverWrite(syncId, kept, now)
        fake.requests.clear()
        assertEquals(SyncOutcome.DONE, a.sync())
        assertEquals(SyncOutcome.DONE, b.sync())
        a.sync()
        b.sync()

        for (device in listOf(a, b)) {
            val row = device.session(syncId)!!
            assertEquals("Standup", row.title)
            assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
        }
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents") })
    }

    @Test
    fun withTheServerDownNothingLocalChanges() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        val before = b.hash(syncId)
        fake.intercept = { MockResponse().setResponseCode(503) }

        assertEquals(SyncOutcome.RETRY, b.sync())

        assertEquals(before, b.hash(syncId))
        assertNotNull(b.status.status.value.lastError)
    }

    /** Sync until nothing moves, and check every device has one version and pushes nothing more. */
    private suspend fun settle(syncId: String) {
        repeat(2) {
            a.sync()
            b.sync()
        }
        assertEquals(a.hash(syncId), b.hash(syncId))
        fake.requests.clear()
        a.sync()
        b.sync()
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents/session") })
    }

    /**
     * A session on both devices, [edit]ed on each while the other can't
     * see it, then synced with each device going first in turn.
     */
    private fun inEitherOrder(edit: suspend (syncId: String) -> Unit, check: suspend (Device, String) -> Unit) =
        runTest {
            for ((first, second) in listOf(a to b, b to a)) {
                val syncId = fullSession(a)
                a.sync()
                b.sync()
                edit(syncId)
                first.sync()
                second.sync()
                settle(syncId)
                check(a, syncId)
                check(b, syncId)
            }
        }

    private suspend fun Device.id(syncId: String) = session(syncId)!!.id

    private suspend fun Device.lines(syncId: String) = repo.getSegments(id(syncId))

    private suspend fun Device.items(syncId: String) = db.actionItemDao().getForSession(id(syncId))

    private suspend fun Device.tags(syncId: String) = db.tagDao().namesFor(id(syncId))

    @Test
    fun aRenameAndALineEditOnDifferentDevicesBothSurvive() = inEitherOrder(
        edit = { syncId ->
            a.repo.renameSession(a.id(syncId), "Renamed on A")
            now += 100
            b.repo.editLineText(b.lines(syncId).single().id, "corrected on B")
        },
        check = { device, syncId ->
            assertEquals("Renamed on A", device.session(syncId)!!.title)
            assertEquals("corrected on B", device.lines(syncId).single().text)
        }
    )

    @Test
    fun aDeviceEditSurvivesAWebEditItNeverSaw() = runTest {
        val syncId = fullSession(a)
        a.sync()
        a.repo.renameSpeaker(a.id(syncId), 0, "Anna")
        now += 100

        fake.serverWrite(syncId, webEdited(syncId), now)
        a.sync()
        b.sync()
        settle(syncId)

        for (device in listOf(a, b)) {
            assertEquals("Renamed on the web", device.session(syncId)!!.title)
            assertEquals("Hello there", device.lines(syncId).single().text)
            assertEquals("Anna", device.repo.getSpeakers(device.id(syncId)).single().label)
        }
    }

    @Test
    fun aDeviceOfflinePastTheServersCleanupStillMerges() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.renameSession(b.id(syncId), "Offline on B")
        now += 8L * 24 * 60 * 60 * 1000
        repeat(3) { round ->
            now += 100
            a.repo.addMark(a.id(syncId), atMs = 200L + round, note = "mark $round")
            a.sync()
        }

        b.sync()
        settle(syncId)

        assertEquals("Offline on B", a.session(syncId)!!.title)
        assertEquals(4, b.repo.getMarks(b.id(syncId)).size)
    }

    @Test
    fun theLaterChangeToOneValueWinsAndTheRestMerge() = inEitherOrder(
        edit = { syncId ->
            b.repo.renameSession(b.id(syncId), "Earlier on B")
            b.repo.renameSpeaker(b.id(syncId), 0, "Anna")
            now += 100
            a.repo.renameSession(a.id(syncId), "Later on A")
        },
        check = { device, syncId ->
            assertEquals("Later on A", device.session(syncId)!!.title)
            assertEquals("Anna", device.repo.getSpeakers(device.id(syncId)).single().label)
        }
    )

    @Test
    fun tagsAddedAndRemovedOnDifferentDevicesAllApply() = inEitherOrder(
        edit = { syncId ->
            a.repo.setSessionTags(a.id(syncId), listOf("from-a"))
            now += 100
            b.repo.setSessionTags(b.id(syncId), listOf("from-b", "work"))
        },
        check = { device, syncId -> assertEquals(listOf("from-a", "from-b"), device.tags(syncId).sorted()) }
    )

    @Test
    fun actionItemsWorkedOnOnDifferentDevicesAllApply() = runTest {
        val syncId = fullSession(a)
        val items = listOf(
            ExtractedActionItem("Call the client"),
            ExtractedActionItem("Write the plan"),
            ExtractedActionItem("Book the room")
        )
        a.repo.replaceExtractedItems(a.id(syncId), items)
        a.sync()
        b.sync()
        val onA = a.items(syncId)
        val onB = b.items(syncId)
        a.repo.setActionItemDone(onA[0].id, true)
        a.repo.deleteActionItem(onA[1].id)
        now += 100
        b.repo.editActionItem(onB[0].id, "Phone the client", "Ann", null)
        b.repo.editActionItem(onB[1].id, "Draft the plan", null, null)
        b.repo.setActionItemDone(onB[2].id, true)

        b.sync()
        a.sync()
        settle(syncId)

        for (device in listOf(a, b)) {
            val (call, plan, room) = device.items(syncId)
            assertEquals("Phone the client", call.text)
            assertEquals("Ann", call.owner)
            assertTrue(call.done)
            assertTrue("a delete sticks", plan.deleted)
            assertTrue(room.done)
        }
    }

    @Test
    fun marksAndQuestionsAddedOnBothDevicesAreAllKept() = inEitherOrder(
        edit = { syncId ->
            now += 10
            a.repo.addMark(a.id(syncId), atMs = 200, note = "from A")
            a.repo.addMessage(a.id(syncId), MessageKind.QA, MessageRole.USER, "asked on A")
            a.repo.renameSession(a.id(syncId), "Touched")
            now += 100
            b.repo.addMark(b.id(syncId), atMs = 300, note = "from B")
            b.repo.addMessage(b.id(syncId), MessageKind.QA, MessageRole.USER, "asked on B")
            b.repo.renameSpeaker(b.id(syncId), 0, "Anna")
        },
        check = { device, syncId ->
            assertEquals(
                listOf("decision", "from A", "from B"),
                device.repo.getMarks(device.id(syncId)).map { it.note }
            )
            assertEquals(
                setOf("asked on A", "asked on B"),
                device.db.chatMessageDao().getForSession(device.id(syncId))
                    .filter { it.kind == MessageKind.QA }.map { it.content }.toSet()
            )
        }
    )

    @Test
    fun aContinuedRecordingWinsWholeAndArrivesWithTheOthersEdits() = inEitherOrder(
        edit = { syncId ->
            val row = a.session(syncId)!!
            File(row.audioPath).writeBytes(LONGER)
            a.repo.addSegment(row.id, startMs = 500, endMs = 900, text = "continued")
            a.repo.setDuration(row.id, 5_000)
            now += 100
            b.repo.renameSession(b.id(syncId), "Renamed on B")
            b.repo.setSessionTags(b.id(syncId), listOf("work", "from-b"))
            b.repo.editLineText(b.lines(syncId).single().id, "lost")
        },
        check = { device, syncId ->
            val row = device.session(syncId)!!
            assertEquals(5_000L, row.durationMs)
            assertEquals(LONGER.toList(), File(row.audioPath).readBytes().toList())
            assertEquals(listOf("hello", "continued"), device.lines(syncId).map { it.text })
            assertEquals("Renamed on B", row.title)
            assertEquals(listOf("from-b", "work"), device.tags(syncId).sorted())
        }
    )

    @Test
    fun aSplitsLinesWinAndAnEditToAnotherLineCarriesOver() = runTest {
        val syncId = fullSession(a)
        a.repo.addSegment(a.id(syncId), startMs = 500, endMs = 900, text = "split this line")
        a.sync()
        b.sync()
        assertNotNull(a.repo.splitLine(a.lines(syncId)[1].id, 6))
        now += 100
        b.repo.editLineText(b.lines(syncId)[0].id, "edited on B")

        b.sync()
        a.sync()
        settle(syncId)

        for (device in listOf(a, b)) {
            assertEquals(listOf("edited on B", "split", "this line"), device.lines(syncId).map { it.text })
        }
    }

    @Test
    fun anEditToALineSplitElsewhereGivesWayAndTheRestStillMerges() = runTest {
        val syncId = fullSession(a)
        a.repo.addSegment(a.id(syncId), startMs = 500, endMs = 900, text = "split this line")
        a.sync()
        b.sync()
        assertNotNull(a.repo.splitLine(a.lines(syncId)[1].id, 6))
        now += 100
        b.repo.editLineText(b.lines(syncId)[1].id, "edited on B")
        b.repo.renameSession(b.id(syncId), "Renamed on B")

        a.sync()
        assertEquals(SyncOutcome.DONE, b.sync())
        settle(syncId)

        assertEquals(listOf("hello", "split", "this line"), b.lines(syncId).map { it.text })
        assertEquals("Renamed on B", a.session(syncId)!!.title)
    }

    @Test
    fun anotherChangeGettingInDuringAMergeIsMergedToo() = runTest {
        val syncId = fullSession(a)
        a.sync()
        a.repo.renameSpeaker(a.id(syncId), 0, "Anna")
        now += 100
        fake.serverWrite(syncId, webEdited(syncId), now)
        var writes = 0
        // The push of the merge finds yet another change on the server.
        fake.intercept = { request ->
            if (request.method == "PUT" && request.path!!.contains("documents/session") && writes++ == 1) {
                val current = Json.parseToJsonElement(fake.documents.getValue(syncId).manifest!!).jsonObject
                val tagged = current + ("tags" to JsonArray(listOf(JsonPrimitive("late"))))
                fake.serverWrite(syncId, JsonObject(tagged).toString(), now)
            }
            null
        }

        assertEquals(SyncOutcome.DONE, a.sync())
        fake.intercept = { null }
        b.sync()
        settle(syncId)

        assertEquals(listOf("late"), a.tags(syncId))
        assertEquals("Renamed on the web", a.session(syncId)!!.title)
        assertEquals("Anna", b.repo.getSpeakers(b.id(syncId)).single().label)
    }

    @Test
    fun aSessionThatKeepsChangingWaitsForTheNextRun() = runTest {
        val syncId = fullSession(a)
        a.sync()
        a.repo.renameSpeaker(a.id(syncId), 0, "Anna")
        var writes = 0
        fake.intercept = { request ->
            if (request.method == "PUT" && request.path!!.contains("documents/session")) {
                val current = Json.parseToJsonElement(fake.documents.getValue(syncId).manifest!!).jsonObject
                val title = "title" to JsonPrimitive("Web ${writes++}")
                fake.serverWrite(syncId, JsonObject(current + title).toString(), now)
            }
            null
        }

        a.sync()
        // The first push and each merge's.
        assertEquals(4, writes)
        fake.intercept = { null }
        a.sync()
        b.sync()
        settle(syncId)

        assertEquals("Anna", b.repo.getSpeakers(b.id(syncId)).single().label)
    }

    @Test
    fun withoutABaseTheLaterChangeWinsAndAdditionsCombine() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        File(a.dir, "sync-base").deleteRecursively()
        a.repo.renameSession(a.id(syncId), "Earlier on A")
        a.repo.addMark(a.id(syncId), atMs = 200, note = "from A")
        now += 100
        b.repo.renameSession(b.id(syncId), "Later on B")
        b.repo.addMark(b.id(syncId), atMs = 300, note = "from B")

        b.sync()
        a.sync()
        settle(syncId)

        assertEquals("Later on B", a.session(syncId)!!.title)
        assertEquals(3, a.repo.getMarks(a.id(syncId)).size)
    }

    @Test
    fun aSessionFromANewerAppIsLeftAloneUntilThisOneIsUpdated() = runTest {
        val syncId = fullSession(a)
        a.sync()
        a.repo.renameSession(a.id(syncId), "Renamed on A")
        val manifest = Json.parseToJsonElement(fake.documents.getValue(syncId).manifest!!).jsonObject
        val newer = JsonObject(manifest + ("schema" to JsonPrimitive(SessionManifests.SCHEMA + 1)))
        fake.serverWrite(syncId, newer.toString(), now)

        assertEquals(SyncOutcome.RETRY, a.sync())

        assertEquals("Renamed on A", a.session(syncId)!!.title)
        assertEquals(newer.toString(), fake.documents.getValue(syncId).manifest)
        assertNotNull(a.db.syncDao().state(SyncKinds.SESSION, syncId)!!.lastError)
    }

    @Test
    fun onlySessionPushesAskToMerge() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        fake.intercept = { request ->
            if (request.method == "PUT" && request.path!!.contains("/documents/")) {
                seen += request.path!!.substringAfter("/documents/").substringBefore('/') to
                    request.getHeader("Sync-Merge")
            }
            null
        }
        a.db.presetDao().insert(
            PresetEntity(
                name = "Preset",
                titlePattern = "",
                language = "en",
                numSpeakers = 0,
                participants = null,
                tagIds = "",
                summaryTemplate = "",
                cleanAudio = false,
                createdAt = 1
            )
        )
        fullSession(a)

        a.sync()

        assertTrue(("session" to "1") in seen)
        assertTrue(seen.filter { it.first != "session" }.isNotEmpty())
        assertTrue(seen.filter { it.first != "session" }.all { it.second == null })
    }

    @Test
    fun aServerThatKnowsNoMergingKeepsItsWholeSessionRule() = runTest {
        fake.honoursMerge = false
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        a.repo.renameSession(a.id(syncId), "On A")
        a.sync()
        now += 100
        b.repo.setDuration(b.id(syncId), 9_000)

        // A stale push the server's rule lets through replaces the version on it.
        b.sync()
        a.sync()
        settle(syncId)

        assertEquals(9_000L, a.session(syncId)!!.durationMs)
        assertEquals("Standup", a.session(syncId)!!.title)
    }

    @Test
    fun aLosingPushOnAServerThatKnowsNoMergingStillMerges() = runTest {
        fake.honoursMerge = false
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.renameSession(b.id(syncId), "Earlier on B")
        now += 100
        a.repo.renameSpeaker(a.id(syncId), 0, "Anna")
        a.sync()

        b.sync()
        settle(syncId)

        assertEquals("Earlier on B", a.session(syncId)!!.title)
        assertEquals("Anna", a.repo.getSpeakers(a.id(syncId)).single().label)
    }

    private companion object {
        val AUDIO = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val CLEAN = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        val LONGER = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 21, 22, 23, 24, 25)
    }
}
