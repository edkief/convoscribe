package com.convoscribe.backup

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.Preset
import com.convoscribe.data.PresetStore
import com.convoscribe.data.SessionManifest
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SessionManifests.toHex
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SummaryTemplateEntity
import com.convoscribe.data.TagNames
import com.convoscribe.data.cleanAudioPathFor
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.SettingsRepository
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Which way a [BackupUseCase] run goes. */
enum class BackupKind { BACKUP, RESTORE }

/** Why a backup or restore stopped; the UI words each one. */
enum class BackupFailure {
    /** The file is not a ConvoScribe backup. */
    NOT_A_BACKUP,

    /** The backup was made by a newer version of the app. */
    TOO_NEW,

    /** The archive is damaged: unreadable, or a recording does not match its hash. */
    DAMAGED,

    /** Reading or writing the file failed. */
    IO
}

/** Progress of [BackupUseCase], observed by Settings and the service notification. */
sealed interface BackupState {
    data object Idle : BackupState

    /** [kind] is [progress] `0f..1f` done, or `null` when the size is unknown. */
    data class Running(val kind: BackupKind, val progress: Float?) : BackupState

    /**
     * Finished: [sessions] were written (backup) or restored, and on a
     * restore [skipped] were already on this device.
     */
    data class Done(val kind: BackupKind, val sessions: Int, val skipped: Int = 0) : BackupState

    /**
     * [kind] stopped with [failure]; [restored] sessions were restored
     * before it did, each complete.
     */
    data class Failed(
        val kind: BackupKind,
        val failure: BackupFailure,
        val message: String? = null,
        val restored: Int = 0
    ) : BackupState
}

/** A backup or restore could not go on; [failure] says why. */
class BackupException(val failure: BackupFailure, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Back up every session to one archive, and restore an archive into
 * this install (issue #25). See [BackupFormat] for the layout.
 *
 * **Backup** writes the header, then each finished session (a session
 * still recording is left out) with its recording and cleaned copy.
 * The API keys are never read.
 *
 * **Restore** checks the header before changing anything: a file that
 * is not a backup, or one from a newer version, is refused. Known
 * voices, templates and presets are added unless an identical one is
 * here; settings are applied when asked. Each session is then applied
 * in one transaction with its recordings moved in last
 * ([SessionRepository.applyManifest]), after their hashes are checked,
 * so a session is restored whole or not at all. A session whose
 * [SessionManifest.syncId] is already here is skipped, which makes a
 * restore idempotent; a cancelled or failed one keeps the sessions it
 * finished, and running it again completes it.
 *
 * Recordings are staged in [stagingDir], which [cleanUp] empties.
 */
class BackupUseCase(
    private val repository: SessionRepository,
    private val db: AppDatabase,
    private val presets: PresetStore,
    private val settings: SettingsRepository,
    private val stagingDir: File,
    private val appVersion: () -> String,
    private val onRestored: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is BackupState.Running

    /** Forget a finished run's result, once the UI has shown it. */
    fun dismiss() {
        if (!isRunning) _state.value = BackupState.Idle
    }

    /** Remove recordings left in [stagingDir] by a run the process did not finish. */
    fun cleanUp() {
        stagingDir.deleteRecursively()
    }

    /**
     * Write a backup of every finished session to [out], which this
     * closes. Returns the number of sessions written, or `null` when it
     * failed ([state] then holds [BackupState.Failed]). Rethrows
     * cancellation after resetting [state]; the caller removes the
     * partial file.
     */
    suspend fun backup(out: OutputStream): Int? = run(BackupKind.BACKUP) {
        withContext(ioDispatcher) { writeBackup(out) }
    }

    /**
     * Restore the backup read from [input], which this closes;
     * [sizeBytes] (if known) drives the progress. [restoreSettings]
     * also applies the backed-up settings. Returns the number of
     * sessions restored, or `null` when it failed.
     */
    suspend fun restore(input: InputStream, sizeBytes: Long?, restoreSettings: Boolean): Int? =
        run(BackupKind.RESTORE) {
            withContext(ioDispatcher) { readBackup(input, sizeBytes, restoreSettings) }
        }

    private suspend fun run(kind: BackupKind, block: suspend () -> BackupState.Done): Int? {
        _state.value = BackupState.Running(kind, 0f)
        return try {
            val done = block()
            _state.value = done
            done.sessions
        } catch (e: CancellationException) {
            _state.value = BackupState.Idle
            throw e
        } catch (e: PartialRestoreException) {
            Log.w(TAG, "Restore stopped after ${e.restored} sessions", e.cause)
            _state.value = failed(kind, e.cause, e.restored)
            null
        } catch (e: Exception) {
            Log.w(TAG, "${kind.name.lowercase()} failed", e)
            _state.value = failed(kind, e, 0)
            null
        } finally {
            cleanUp()
        }
    }

    private fun failed(kind: BackupKind, e: Throwable, restored: Int): BackupState.Failed = when (e) {
        is BackupException -> BackupState.Failed(kind, e.failure, e.message, restored)
        is ZipException, is EOFException, is SerializationException, is IllegalArgumentException ->
            BackupState.Failed(kind, BackupFailure.DAMAGED, e.message, restored)
        else -> BackupState.Failed(kind, BackupFailure.IO, e.message, restored)
    }

    // --- Backup ---

    private suspend fun writeBackup(out: OutputStream): BackupState.Done {
        val ids = db.sessionDao().allIds()
        val sessions = ids.mapNotNull { repository.getSession(it) }.filter { it.status != SessionStatus.RECORDING }
        val totalBytes = sessions.sumOf { session ->
            val clean = if (session.cleanedWith != null) File(cleanAudioPathFor(session.audioPath)).length() else 0L
            File(session.audioPath).length() + clean
        }.coerceAtLeast(1L)
        var doneBytes = 0L
        var written = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.HEADER_ENTRY))
            zip.write(BackupJson.encodeHeader(header(sessions.size)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (session in sessions) {
                currentCoroutineContext().ensureActive()
                val digests = HashMap<String, Digest>()
                val snapshot = repository.snapshot(session.id) { file ->
                    digest(file)?.also { digests[it.sha256] = it }?.ref
                } ?: continue
                if (snapshot.manifest.status == SessionStatus.RECORDING.name) continue
                zip.putNextEntry(ZipEntry(BackupFormat.sessionEntry(snapshot.manifest.syncId)))
                zip.write(
                    BackupJson.encodeSession(
                        SessionEntry(snapshot.updatedAt, snapshot.manifest)
                    ).toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
                for ((sha, file) in snapshot.files) {
                    val digest = digests.getValue(sha)
                    val entry = ZipEntry(BackupFormat.recordingEntry(sha)).apply {
                        method = ZipEntry.STORED
                        size = digest.size
                        compressedSize = digest.size
                        crc = digest.crc
                    }
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input ->
                        copy(input, zip) { n ->
                            doneBytes += n
                            val progress = (doneBytes.toFloat() / totalBytes).coerceAtMost(1f)
                            _state.value = BackupState.Running(BackupKind.BACKUP, progress)
                        }
                    }
                    zip.closeEntry()
                }
                written++
            }
        }
        return BackupState.Done(BackupKind.BACKUP, written)
    }

    private suspend fun header(sessionCount: Int): BackupHeader {
        val templates = db.summaryTemplateDao().getAll()
        val templateNames = templates.associate { it.id to it.name }
        val ref = { key: String -> SessionManifests.templateRef(key, templateNames) }
        return BackupHeader(
            format = BackupFormat.FORMAT,
            version = BackupFormat.VERSION,
            createdAt = clock(),
            appVersion = appVersion(),
            sessionCount = sessionCount,
            settings = PortableSettings.from(settings.settings.value, ref),
            voices = db.enrolledSpeakerDao().getAll().map {
                BackupHeader.Voice(it.name, Base64.getEncoder().encodeToString(it.embedding), it.createdAt)
            },
            templates = templates.map { BackupHeader.Template(it.name, it.body, it.createdAt) },
            presets = presets.observeAll().first().map { preset ->
                BackupHeader.PresetDoc(
                    name = preset.name,
                    titlePattern = preset.titlePattern,
                    language = preset.language,
                    numSpeakers = preset.numSpeakers,
                    participants = preset.participants,
                    tags = preset.tags,
                    summaryTemplate = ref(preset.summaryTemplate),
                    cleanAudio = preset.cleanAudio
                )
            }
        )
    }

    /** A recording's hash, size and CRC, read in one pass; the zip entry needs the CRC up front. */
    private class Digest(val ref: SessionManifest.FileRef, val crc: Long) {
        val sha256: String get() = ref.sha256
        val size: Long get() = ref.size
    }

    private fun digest(file: File): Digest? {
        if (!file.isFile) return null
        val sha = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
                crc.update(buffer, 0, read)
                size += read
            }
        }
        return Digest(SessionManifest.FileRef(sha.digest().toHex(), size), crc.value)
    }

    // --- Restore ---

    /** Thrown once some sessions were restored, so the result can say how many. */
    private class PartialRestoreException(val restored: Int, override val cause: Throwable) : Exception(cause)

    /** A session read from the archive, waiting for its recordings. */
    private class Pending(val entry: SessionEntry) {
        val needed: Set<String> = listOfNotNull(
            entry.manifest.audio,
            entry.manifest.cleanAudio
        ).map { it.sha256 }.toSet()
        val staged = HashMap<String, File>()
    }

    private suspend fun readBackup(input: InputStream, sizeBytes: Long?, restoreSettings: Boolean): BackupState.Done {
        val counting = CountingInputStream(input.buffered())
        val progress = {
            _state.value = BackupState.Running(
                BackupKind.RESTORE,
                sizeBytes?.takeIf { it > 0 }?.let { (counting.count.toFloat() / it).coerceAtMost(1f) }
            )
        }
        var restored = 0
        var skipped = 0
        ZipInputStream(counting).use { zip ->
            val header = readHeader(zip)
            val templateKey = restoreLibrary(header)
            if (restoreSettings) {
                header.settings?.let { portable ->
                    settings.update {
                        portable.applyTo(
                            it,
                            templateKey
                        )
                    }
                }
            }
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            var pending: Pending? = null
            try {
                suspend fun flush() {
                    val session = pending ?: return
                    pending = null
                    repository.applyManifest(
                        session.entry.manifest,
                        session.entry.updatedAt,
                        session.staged,
                        templateKey
                    )
                    session.staged.values.forEach { it.delete() }
                    restored++
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val sha = BackupFormat.recordingSha(name)
                    when {
                        name.startsWith(BackupFormat.SESSION_PREFIX) -> {
                            flush()
                            val session = BackupJson.decodeSession(zip.readBytes().toString(Charsets.UTF_8))
                            if (session.manifest.schema > SessionManifests.SCHEMA) {
                                throw BackupException(
                                    BackupFailure.TOO_NEW,
                                    "Session schema ${session.manifest.schema}"
                                )
                            }
                            if (db.sessionDao().getBySyncId(session.manifest.syncId) != null) {
                                skipped++
                            } else {
                                pending = Pending(session)
                            }
                        }
                        sha != null && pending?.needed?.contains(sha) == true -> {
                            val target = File(stagingDir, sha)
                            stage(zip, target, sha, progress)
                            pending!!.staged[sha] = target
                        }
                    }
                    progress()
                }
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (restored > 0) throw PartialRestoreException(restored, e)
                throw e
            } finally {
                if (restored > 0) onRestored()
            }
        }
        return BackupState.Done(BackupKind.RESTORE, restored, skipped)
    }

    private fun readHeader(zip: ZipInputStream): BackupHeader {
        val first = try {
            zip.nextEntry
        } catch (e: ZipException) {
            throw BackupException(BackupFailure.NOT_A_BACKUP, "Not a zip archive", e)
        }
        if (first?.name != BackupFormat.HEADER_ENTRY) {
            throw BackupException(
                BackupFailure.NOT_A_BACKUP,
                "No ${BackupFormat.HEADER_ENTRY}"
            )
        }
        val header = try {
            BackupJson.decodeHeader(zip.readBytes().toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupException(BackupFailure.NOT_A_BACKUP, "Unreadable ${BackupFormat.HEADER_ENTRY}", e)
        }
        if (header.format != BackupFormat.FORMAT) {
            throw BackupException(
                BackupFailure.NOT_A_BACKUP,
                "Format ${header.format}"
            )
        }
        if (header.version > BackupFormat.VERSION) {
            throw BackupException(
                BackupFailure.TOO_NEW,
                "Backup version ${header.version}, app reads ${BackupFormat.VERSION}"
            )
        }
        return upgrade(header)
    }

    /**
     * Bring a header from an older version up to [BackupFormat.VERSION].
     * Version 1 is the first, so there is nothing to do yet.
     */
    private fun upgrade(header: BackupHeader): BackupHeader = header

    /**
     * Add the header's voices, templates and presets that are not here
     * yet. Returns how a template reference resolves on this install.
     */
    private suspend fun restoreLibrary(header: BackupHeader): (SessionManifest.TemplateRef) -> String? {
        val voiceDao = db.enrolledSpeakerDao()
        val voices = voiceDao.getAll()
        for (voice in header.voices) {
            val embedding = Base64.getDecoder().decode(voice.embedding)
            if (voices.none { it.name == voice.name && it.embedding.contentEquals(embedding) }) {
                voiceDao.insert(
                    EnrolledSpeakerEntity(name = voice.name, embedding = embedding, createdAt = voice.createdAt)
                )
            }
        }

        val templateDao = db.summaryTemplateDao()
        val existing = templateDao.getAll()
        val keyByName = HashMap<String, String>()
        existing.forEach { keyByName.putIfAbsent(it.name, SummaryTemplates.userKey(it.id)) }
        for (template in header.templates) {
            val same = existing.firstOrNull { it.name == template.name && it.body == template.body }
            val id = same?.id ?: templateDao.insert(
                SummaryTemplateEntity(name = template.name, body = template.body, createdAt = template.createdAt)
            )
            keyByName[template.name] = SummaryTemplates.userKey(id)
        }
        val templateKey = { ref: SessionManifest.TemplateRef -> ref.key ?: ref.user?.let(keyByName::get) }

        val here = presets.observeAll().first().map { it.copy(id = 0, tags = it.tags.sortedBy(TagNames::key)) }
        for (doc in header.presets) {
            val preset = Preset(
                name = doc.name,
                titlePattern = doc.titlePattern,
                language = doc.language,
                numSpeakers = doc.numSpeakers,
                participants = doc.participants,
                tags = doc.tags.sortedBy(TagNames::key),
                summaryTemplate = doc.summaryTemplate?.let(templateKey) ?: SummaryTemplates.MEETING_KEY,
                cleanAudio = doc.cleanAudio
            )
            if (preset !in here) presets.save(preset)
        }
        return templateKey
    }

    /** Copy the current entry of [zip] to [target], checking it hashes to [sha]. */
    private suspend fun stage(zip: ZipInputStream, target: File, sha: String, progress: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            copy(zip, out) { _ -> progress() }
        }
        FileInputStream(target).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().toHex() != sha) {
            target.delete()
            throw BackupException(BackupFailure.DAMAGED, "Recording $sha does not match its hash")
        }
    }

    private suspend fun copy(input: InputStream, out: OutputStream, onChunk: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            onChunk(read)
        }
    }

    /** Counts the bytes read, for a restore's progress. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(
            b,
            off,
            len
        ).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** JSON of the archive's entries. */
internal object BackupJson {
    private val json = SessionManifests.json

    fun encodeHeader(header: BackupHeader): String = json.encodeToString(BackupHeader.serializer(), header)

    fun decodeHeader(text: String): BackupHeader = json.decodeFromString(BackupHeader.serializer(), text)

    fun encodeSession(entry: SessionEntry): String = json.encodeToString(SessionEntry.serializer(), entry)

    fun decodeSession(text: String): SessionEntry = json.decodeFromString(SessionEntry.serializer(), text)
}
