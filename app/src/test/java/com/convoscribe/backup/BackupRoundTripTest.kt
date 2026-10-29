package com.convoscribe.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.TimedToken
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.Preset
import com.convoscribe.data.PresetRepository
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.SummaryTemplateEntity
import com.convoscribe.data.cleanAudioPathFor
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Back up, wipe, restore (issue #25). */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "backup-test-${System.nanoTime()}")
    private val devices = mutableListOf<Device>()

    /** One install: its database, recordings, settings and secrets. */
    private inner class Device(name: String) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val dir = File(root, name).apply { mkdirs() }
        val repo = SessionRepository(db, File(dir, "recordings").apply { mkdirs() })
        val secrets = InMemorySecretStore()
        val settings =
            SettingsRepository(
                context.getSharedPreferences("backup-test-$name-${System.nanoTime()}", Context.MODE_PRIVATE),
                secrets
            )
        val presets = PresetRepository(db)
        var restoredCalls = 0
        val backup = BackupUseCase(
            repository = repo,
            db = db,
            presets = presets,
            settings = settings,
            stagingDir = File(dir, "staging"),
            appVersion = { "0.15.0" },
            onRestored = { restoredCalls++ },
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 1_000L }
        )

        init {
            devices += this
        }

        suspend fun syncIds(): List<String> = db.sessionDao().allIds().map { repo.getSession(it)!!.syncId }

        suspend fun hashes(): Map<String, String> = db.sessionDao().allIds().associate { id ->
            val snapshot = repo.snapshot(id)!!
            snapshot.manifest.syncId to SessionManifests.hash(snapshot.manifest)
        }
    }

    @After
    fun tearDown() {
        devices.forEach { it.db.close() }
        root.deleteRecursively()
    }

    /** A device with three full sessions, one still recording, a voice, a template, a preset and settings. */
    private suspend fun phone(): Device = Device("phone").apply {
        val templateId = db.summaryTemplateDao().insert(
            SummaryTemplateEntity(name = "Standup", body = "Who did what.", createdAt = 5)
        )
        val template = SummaryTemplates.userKey(templateId)
        for (n in 1..3) {
            val id = repo.createSession(
                title = "Session $n",
                language = "en",
                participants = listOf("Ann", "Bob"),
                summaryTemplate = template,
                tags = listOf("Acme", "week $n")
            )
            repo.addSegment(id, 0, 900, "hello there", listOf(TimedToken("hello", 0), TimedToken(" there", 450)))
            repo.addSegment(
                id,
                1000,
                1900,
                "fine thanks",
                listOf(TimedToken("fine", 1000), TimedToken(" thanks", 1400))
            )
            repo.addMark(id, 300, "budget")
            db.markDao().setExpanded(repo.getMarks(id).first().id, "The budget was discussed.", 7)
            repo.finishRecording(id, 2000)
            File(repo.audioPath(id)).writeBytes(ByteArray(200_000) { (it * n).toByte() })
            db.sessionDao().setCleanedWith(id, "gtcrn")
            File(cleanAudioPathFor(repo.audioPath(id))).writeBytes(ByteArray(150_000) { (it + n).toByte() })
            db.speakerDao().upsert(listOf(SpeakerEntity(id, 0, "Ann"), SpeakerEntity(id, 1, "Speaker 2")))
            db.segmentDao().setSpeaker(repo.getSegments(id)[0].id, 0)
            repo.setStatus(id, SessionStatus.DIARIZED)
            repo.addChatMessage(
                ChatMessageEntity(
                    sessionId = id,
                    kind = MessageKind.SUMMARY,
                    role = MessageRole.ASSISTANT,
                    content = "Summary $n",
                    createdAt = 10,
                    templateName = "Standup"
                )
            )
            repo.addChatMessage(
                ChatMessageEntity(
                    sessionId = id,
                    kind = MessageKind.QA,
                    role = MessageRole.USER,
                    content = "Who?",
                    createdAt = 11
                )
            )
            repo.addChatMessage(
                ChatMessageEntity(
                    sessionId = id,
                    kind = MessageKind.QA,
                    role = MessageRole.ASSISTANT,
                    content = "Ann.",
                    createdAt = 12,
                    coveredMs = 900,
                    totalMs = 2000
                )
            )
        }
        repo.createSession(title = "Still recording", language = "en")
        db.enrolledSpeakerDao().insert(
            EnrolledSpeakerEntity(name = "Ann", embedding = byteArrayOf(1, 2, 3, 4), createdAt = 3)
        )
        presets.save(
            Preset(
                name = "Weekly",
                language = "fr",
                tags = listOf("Acme"),
                summaryTemplate = template,
                cleanAudio = true
            )
        )
        settings.update {
            it.copy(llmModel = "llama", llmBaseUrl = "http://llm", defaultSummaryTemplate = template, numThreads = 2)
        }
        secrets.setApiKey("sk-super-secret-llm")
        secrets.setAsrServerKey("sk-super-secret-asr")
    }

    private suspend fun archiveOf(device: Device): ByteArray {
        val out = ByteArrayOutputStream()
        assertEquals(3, device.backup.backup(out))
        return out.toByteArray()
    }

    private suspend fun Device.restoreFrom(bytes: ByteArray, restoreSettings: Boolean = true): Int? =
        backup.restore(ByteArrayInputStream(bytes), bytes.size.toLong(), restoreSettings)

    @Test
    fun backupWipeRestore_rebuildsEverySessionIdentically() = runTest {
        val phone = phone()
        val archive = archiveOf(phone)
        val tablet = Device("tablet")

        assertEquals(3, tablet.restoreFrom(archive))

        val expected = phone.hashes().filterKeys { syncId ->
            phone.db.sessionDao().getBySyncId(syncId)!!.status != SessionStatus.RECORDING
        }
        assertEquals(expected, tablet.hashes())
        for (syncId in expected.keys) {
            val from = phone.db.sessionDao().getBySyncId(syncId)!!
            val to = tablet.db.sessionDao().getBySyncId(syncId)!!
            assertArrayEquals(File(from.audioPath).readBytes(), File(to.audioPath).readBytes())
            assertArrayEquals(
                File(cleanAudioPathFor(from.audioPath)).readBytes(),
                File(cleanAudioPathFor(to.audioPath)).readBytes()
            )
            assertEquals(from.updatedAt, to.updatedAt)
        }
        assertEquals(BackupState.Done(BackupKind.RESTORE, sessions = 3, skipped = 0), tablet.backup.state.value)
        assertEquals(1, tablet.restoredCalls)
        assertEquals(listOf("Ann"), tablet.db.enrolledSpeakerDao().getAll().map { it.name })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), tablet.db.enrolledSpeakerDao().getAll().single().embedding)
        val template = tablet.db.summaryTemplateDao().getAll().single()
        val templateKey = SummaryTemplates.userKey(template.id)
        assertEquals("Standup", template.name)
        val preset = tablet.presets.observeAll().first().single()
        assertEquals(templateKey, preset.summaryTemplate)
        assertEquals(listOf("Acme"), preset.tags)
        val settings = tablet.settings.settings.value
        assertEquals("llama", settings.llmModel)
        assertEquals(templateKey, settings.defaultSummaryTemplate)
        assertEquals("device-specific settings are not restored", 4, settings.numThreads)
        assertEquals("", tablet.secrets.getApiKey())
        assertTrue(File(tablet.dir, "staging").listFiles().isNullOrEmpty())
    }

    @Test
    fun restore_isIdempotent() = runTest {
        val phone = phone()
        val archive = archiveOf(phone)
        val tablet = Device("tablet")
        tablet.restoreFrom(archive)
        val hashes = tablet.hashes()

        assertEquals(0, tablet.restoreFrom(archive))

        assertEquals(BackupState.Done(BackupKind.RESTORE, sessions = 0, skipped = 3), tablet.backup.state.value)
        assertEquals(hashes, tablet.hashes())
        assertEquals(1, tablet.db.enrolledSpeakerDao().getAll().size)
        assertEquals(1, tablet.db.summaryTemplateDao().getAll().size)
        assertEquals(1, tablet.presets.observeAll().first().size)
    }

    @Test
    fun restore_withoutSettingsKeepsThem() = runTest {
        val archive = archiveOf(phone())
        val tablet = Device("tablet")
        tablet.restoreFrom(archive, restoreSettings = false)
        assertEquals("", tablet.settings.settings.value.llmModel)
    }

    @Test
    fun backup_neverHoldsTheApiKeys() = runTest {
        val archive = archiveOf(phone())
        val entries = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList()
        }
        assertEquals(BackupFormat.HEADER_ENTRY, entries.first().first)
        for ((_, bytes) in entries) {
            val text = String(bytes, Charsets.ISO_8859_1)
            assertFalse(text.contains("sk-super-secret"))
        }
    }

    @Test
    fun restore_refusesANewerBackupBeforeChangingAnything() = runTest {
        val header = BackupHeader(format = BackupFormat.FORMAT, version = BackupFormat.VERSION + 1, createdAt = 1)
        val tablet = Device("tablet")

        assertNull(
            tablet.restoreFrom(zipOf(BackupFormat.HEADER_ENTRY to BackupJson.encodeHeader(header).toByteArray()))
        )

        val state = tablet.backup.state.value as BackupState.Failed
        assertEquals(BackupFailure.TOO_NEW, state.failure)
        assertEquals(emptyList<String>(), tablet.syncIds())
    }

    @Test
    fun restore_refusesAFileThatIsNotABackup() = runTest {
        val tablet = Device("tablet")
        assertNull(tablet.restoreFrom("just some text".toByteArray()))
        assertEquals(BackupFailure.NOT_A_BACKUP, (tablet.backup.state.value as BackupState.Failed).failure)

        assertNull(tablet.restoreFrom(zipOf("notes.txt" to "hi".toByteArray())))
        assertEquals(BackupFailure.NOT_A_BACKUP, (tablet.backup.state.value as BackupState.Failed).failure)
    }

    @Test
    fun restore_ofADamagedRecordingKeepsOnlyCompleteSessions() = runTest {
        val phone = phone()
        val archive = archiveOf(phone)
        // Flip a byte in the second session's recording, with a valid CRC,
        // so only its hash gives it away.
        var recordings = 0
        val damaged = rezip(archive) { name, bytes ->
            if (BackupFormat.recordingSha(name) != null && recordings++ == 2) {
                bytes.copyOf().also {
                    it[100] = (it[100] + 1).toByte()
                }
            } else {
                bytes
            }
        }
        val tablet = Device("tablet")

        assertNull(tablet.restoreFrom(damaged))

        val state = tablet.backup.state.value as BackupState.Failed
        assertEquals(BackupFailure.DAMAGED, state.failure)
        assertEquals(1, state.restored)
        assertEquals("settings wait for a restore that finishes", "", tablet.settings.settings.value.llmModel)
        val restored = tablet.hashes()
        assertEquals(1, restored.size)
        assertEquals(phone.hashes().filterKeys { it in restored }, restored)
        assertTrue(File(tablet.dir, "staging").listFiles().isNullOrEmpty())

        // Running it again with a good archive completes the rest.
        assertEquals(2, tablet.restoreFrom(archive))
        assertEquals(3, tablet.syncIds().size)
        assertEquals("llama", tablet.settings.settings.value.llmModel)
    }

    @Test
    fun restore_cancelledHalfwayKeepsOnlyCompleteSessions() = runTest {
        val phone = phone()
        val archive = archiveOf(phone)
        val tablet = Device("tablet")
        lateinit var job: Job
        val input = object : FilterInputStream(ByteArrayInputStream(archive)) {
            var read = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                read += it.coerceAtLeast(0)
                if (read > archive.size / 2) job.cancel()
            }
        }
        val run = async { tablet.backup.restore(input as InputStream, archive.size.toLong(), restoreSettings = true) }
        job = run

        val result = runCatching { run.await() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(BackupState.Idle, tablet.backup.state.value)
        val restored = tablet.hashes()
        assertTrue(restored.size in 1..2)
        assertEquals(phone.hashes().filterKeys { it in restored }, restored)
        for (syncId in restored.keys) {
            assertNotNull(File(tablet.db.sessionDao().getBySyncId(syncId)!!.audioPath).takeIf { it.isFile })
        }
        assertTrue(File(tablet.dir, "staging").listFiles().isNullOrEmpty())
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** [archive] with each entry's bytes passed through [transform], recordings still stored. */
    private fun rezip(archive: ByteArray, transform: (String, ByteArray) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = transform(entry.name, input.readBytes())
                    val copy = ZipEntry(entry.name)
                    if (entry.method == ZipEntry.STORED) {
                        copy.method = ZipEntry.STORED
                        copy.size = bytes.size.toLong()
                        copy.compressedSize = bytes.size.toLong()
                        copy.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(copy)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
