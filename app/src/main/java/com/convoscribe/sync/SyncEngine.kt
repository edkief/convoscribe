package com.convoscribe.sync

import android.util.Log
import androidx.room.withTransaction
import com.convoscribe.TAG
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionManifest
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionSnapshot
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SyncKinds
import com.convoscribe.data.SyncStateEntity
import com.convoscribe.data.SyncTombstoneEntity
import com.convoscribe.data.cleanAudioFile
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.models.ModelDownloadException
import com.convoscribe.models.ResumableFetch
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient

/** How a [SyncEngine.run] ended, which tells the job whether to retry. */
enum class SyncOutcome {
    /** Everything that could be uploaded and downloaded was. */
    DONE,

    /** Done, but recordings wait for an unmetered network (#69). */
    AUDIO_WAITING,

    /** Sync is off or has no server. */
    DISABLED,

    /** A recording is running; try again once it stops. */
    DEFERRED,

    /** The network or the server failed; try again later. */
    RETRY
}

/**
 * Two-way sync with the sync server (issues #68, #69). A run:
 *
 * 1. reads the change feed and notes, in each [SyncStateEntity], the
 *    newest revision the server has;
 * 2. pushes deletes ([SyncTombstoneEntity]), with the deleted length
 *    for the conflict rule;
 * 3. pushes every session whose manifest hashes to something other
 *    than what the server last accepted, recordings before the
 *    manifest that names them;
 * 4. downloads the sessions the server has a newer revision of, when
 *    the local copy is unchanged or lost the conflict rule, which the
 *    server applies to a push or delete from a stale revision;
 * 5. downloads the recordings of downloaded sessions, on an unmetered
 *    network unless *Download recordings on mobile data* is on.
 *
 * A downloaded session is applied in one transaction before its
 * recordings arrive, and a recording is checked against its hash
 * before it is put in place, so a failing server never leaves a
 * half-applied session; a failure changes nothing but sync's own
 * bookkeeping. A session still recording or identifying its speakers
 * waits for the next run, and the run stops as soon as a recording
 * starts. One run at a time.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val status: SyncStatusStore,
    private val isRecording: () -> Boolean,
    private val downloadDir: File,
    private val isMetered: () -> Boolean = { false },
    private val onRecordingAttached: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val partBytes: Long = BlobUploader.PART_BYTES
) {
    private val lock = Mutex()
    private val dao = db.syncDao()
    private val hashes = FileHashCache(dao)

    suspend fun run(): SyncOutcome = lock.withLock {
        val current = settings.settings.value
        if (!current.syncActive) return@withLock SyncOutcome.DISABLED
        if (isRecording()) return@withLock SyncOutcome.DEFERRED
        val client = SyncClient(http, current.syncServerUrl, settings.syncKey())
        status.update { it.copy(running = true) }
        try {
            Run(client, current.syncServerUrl, BlobUploader(client, dao, partBytes)).execute()
        } finally {
            status.update { it.copy(running = false) }
        }
    }

    /**
     * [row]'s manifest as sync sees it: recordings by their cached
     * hash, and one still downloading by the reference it waits for
     * ([state]), so a session whose recording has not arrived never
     * counts as changed.
     */
    private suspend fun snapshot(row: SessionEntity, state: SyncStateEntity?): SessionSnapshot? {
        val audio = row.audioPath.takeIf { it.isNotBlank() }?.let(::File)
        val clean = audio?.let { row.cleanAudioFile() }?.takeIf { row.cleanedWith != null }
        val refs = buildMap {
            if (audio != null) (hashes.ref(audio) ?: state?.pendingAudio)?.let { put(audio.path, it) }
            if (clean != null) (hashes.ref(clean) ?: state?.pendingClean)?.let { put(clean.path, it) }
        }
        return repository.snapshot(row.id) { file -> refs[file.path] }
    }

    private inner class Run(
        private val client: SyncClient,
        private val server: String,
        private val uploader: BlobUploader
    ) {
        private var waiting = 0
        private var error: String? = null
        private var offline = false
        private var deferred = false
        private var audioWaiting = false

        suspend fun execute(): SyncOutcome {
            pull()
            if (!offline) pushTombstones()
            if (!offline && !deferred) pushSessions()
            if (!offline && !deferred) downloadSessions()
            if (!offline && !deferred) downloadRecordings()
            val downloading = dao.behind(SyncKinds.SESSION).size +
                dao.pendingAudio(SyncKinds.SESSION).sumOf { listOfNotNull(it.audioSha, it.cleanSha).size }
            status.update { previous ->
                when {
                    offline -> previous.copy(lastError = error, downloading = downloading)
                    deferred -> previous.copy(waiting = maxOf(previous.waiting, waiting), downloading = downloading)
                    else -> previous.copy(
                        waiting = waiting,
                        downloading = downloading,
                        lastError = error,
                        lastSyncedAt = if (error == null) clock() else previous.lastSyncedAt
                    )
                }
            }
            return when {
                offline || error != null -> SyncOutcome.RETRY
                deferred -> SyncOutcome.DEFERRED
                audioWaiting -> SyncOutcome.AUDIO_WAITING
                else -> SyncOutcome.DONE
            }
        }

        /**
         * Note in each document's state the newest revision the feed
         * shows, keeping the cursor after each page so an interrupted
         * run picks up where it stopped. Nothing is downloaded yet
         * ([downloadSessions] does that).
         */
        private suspend fun pull() {
            var since = status.cursor(server)
            try {
                do {
                    val page = client.changes(since, FEED_PAGE)
                    for (change in page.changes) noteRemote(change)
                    since = page.next
                    status.setCursor(server, since)
                } while (page.more)
            } catch (e: SyncException) {
                fail(e)
            }
        }

        private suspend fun noteRemote(change: DocumentMeta) {
            if (change.kind != SyncKinds.SESSION) return
            val state = dao.state(change.kind, change.syncId)
            if (state != null && change.revision <= maxOf(state.baseRevision, state.remoteRevision)) return
            if (state?.excluded == true && change.deleted) {
                // Removed here, now deleted everywhere: nothing left to download.
                dao.deleteState(change.kind, change.syncId)
                return
            }
            dao.upsertState(
                (state ?: SyncStateEntity(change.kind, change.syncId)).copy(
                    remoteRevision = change.revision,
                    remoteDeleted = change.deleted
                )
            )
        }

        private suspend fun pushTombstones() {
            orphansToTombstones()
            for (tombstone in dao.tombstones()) {
                // Left by a delete the process died in before the session went.
                if (db.sessionDao().getBySyncId(tombstone.syncId) != null) {
                    dao.deleteTombstone(tombstone.kind, tombstone.syncId)
                    continue
                }
                try {
                    val result = client.deleteDocument(
                        tombstone.kind,
                        tombstone.syncId,
                        tombstone.baseRevision,
                        tombstone.durationMs
                    )
                    db.withTransaction {
                        dao.deleteTombstone(tombstone.kind, tombstone.syncId)
                        if (result is PushResult.Conflict) {
                            // A longer recording from another device beat the delete: download it.
                            dao.upsertState(
                                SyncStateEntity(
                                    tombstone.kind,
                                    tombstone.syncId,
                                    remoteRevision = result.current?.revision ?: (tombstone.baseRevision + 1)
                                )
                            )
                        } else {
                            dao.deleteState(tombstone.kind, tombstone.syncId)
                        }
                    }
                } catch (e: SyncException) {
                    if (fail(e)) return
                }
            }
        }

        /**
         * A session the server has that is gone here without a tombstone
         * (deleted while its upload was finishing, or the process died
         * mid-delete) is deleted on the server too. One removed from this
         * device only ([SyncStateEntity.excluded]) is not.
         */
        private suspend fun orphansToTombstones() {
            for (state in dao.states(SyncKinds.SESSION)) {
                if (state.baseRevision == 0L || state.excluded) continue
                if (db.sessionDao().getBySyncId(state.syncId) != null) continue
                dao.upsertTombstone(
                    SyncTombstoneEntity(state.kind, state.syncId, state.baseRevision, clock(), state.durationMs)
                )
                dao.deleteState(state.kind, state.syncId)
            }
        }

        private suspend fun pushSessions() {
            val sessions = db.sessionDao()
            for (id in sessions.allIds()) {
                if (isRecording()) {
                    deferred = true
                    return
                }
                val row = sessions.get(id) ?: continue
                if (row.status == SessionStatus.RECORDING || row.status == SessionStatus.DIARIZING) {
                    waiting++
                    continue
                }
                try {
                    if (!push(row)) waiting++
                } catch (e: SyncException) {
                    if (fail(e)) return
                }
            }
        }

        /**
         * Record [e]; true when every other call would fail the same way
         * (no network, or a key the server refuses), so the run stops.
         */
        private fun fail(e: SyncException): Boolean {
            Log.w(TAG, "sync: ${e.message}", e)
            waiting++
            error = e.message
            offline = e.offline || e.code == 401
            return offline
        }

        /** Upload [row] if it changed; true when the server is up to date with it. */
        private suspend fun push(row: SessionEntity): Boolean {
            val state = dao.state(SyncKinds.SESSION, row.syncId) ?: SyncStateEntity(SyncKinds.SESSION, row.syncId)
            // Lost the conflict rule: the server's version replaces this one.
            if (state.conflict) return false
            val snapshot = snapshot(row, state) ?: return true
            val manifest = snapshot.manifest
            val hash = SessionManifests.hash(manifest)
            if (hash == state.baseHash) return true
            val pending = if (state.pendingHash == hash) {
                state
            } else {
                state.copy(
                    pendingHash = hash,
                    pendingSince = clock()
                )
            }
            if (pending != state) dao.upsertState(pending)

            try {
                for (ref in listOfNotNull(manifest.audio, manifest.cleanAudio)) {
                    // A recording still downloading is on the server already.
                    val file = snapshot.files[ref.sha256]?.takeIf { it.isFile } ?: continue
                    uploader.ensure(ref, file)
                }
                val result = client.putDocument(
                    kind = SyncKinds.SESSION,
                    syncId = manifest.syncId,
                    manifest = SessionManifests.encode(manifest).toByteArray(Charsets.UTF_8),
                    manifestHash = hash,
                    baseRevision = state.baseRevision,
                    durationMs = manifest.durationMs,
                    changedAt = snapshot.updatedAt
                )
                return when (result) {
                    is PushResult.Accepted -> {
                        accepted(pending, result.meta.revision, hash)
                        true
                    }
                    is PushResult.Conflict -> {
                        val current = result.current
                        dao.upsertState(
                            pending.copy(
                                conflict = true,
                                remoteRevision = maxOf(
                                    pending.remoteRevision,
                                    current?.revision ?: (pending.baseRevision + 1)
                                ),
                                remoteDeleted = current?.deleted ?: pending.remoteDeleted,
                                lastError = null
                            )
                        )
                        false
                    }
                    is PushResult.MissingBlobs -> throw SyncException(
                        "The sync server is missing ${result.missing.size} recording(s) of \"${manifest.title}\"",
                        code = 422
                    )
                    PushResult.NotFound -> throw SyncException("Unexpected 404 for ${manifest.syncId}", code = 404)
                }
            } catch (e: SyncException) {
                dao.upsertState(pending.copy(lastError = e.message))
                throw e
            }
        }

        /**
         * Store what the server accepted. A session deleted while it was
         * being pushed found no state to make a tombstone from, so it
         * gets one here and the server copy goes too.
         */
        private suspend fun accepted(pending: SyncStateEntity, revision: Long, hash: String) = db.withTransaction {
            val row = db.sessionDao().getBySyncId(pending.syncId)
            if (row == null) {
                dao.upsertTombstone(SyncTombstoneEntity(pending.kind, pending.syncId, revision, clock()))
                dao.deleteState(pending.kind, pending.syncId)
            } else {
                dao.upsertState(
                    pending.copy(
                        baseRevision = revision,
                        baseHash = hash,
                        pendingHash = null,
                        pendingSince = null,
                        lastError = null
                    )
                )
            }
        }

        /**
         * Apply every session the server has a newer revision of, unless
         * it changed here since it last synced and did not lose the
         * conflict rule (its push settles it), its delete here is still
         * to be sent, or it is recording or identifying speakers.
         */
        private suspend fun downloadSessions() {
            val tombstoned = dao.tombstones().map { it.syncId }.toSet()
            val templateKeys = db.summaryTemplateDao().getAll().associate { it.name to SummaryTemplates.userKey(it.id) }
            for (state in dao.behind(SyncKinds.SESSION)) {
                if (isRecording()) {
                    deferred = true
                    return
                }
                if (state.syncId in tombstoned) continue
                val row = db.sessionDao().getBySyncId(state.syncId)
                if (row != null && (row.status == SessionStatus.RECORDING || row.status == SessionStatus.DIARIZING)) {
                    waiting++
                    continue
                }
                if (row != null && !state.conflict) {
                    val hash = snapshot(row, state)?.manifest?.let(SessionManifests::hash)
                    if (hash != state.baseHash) continue
                }
                try {
                    when (val fetched = client.getDocument(SyncKinds.SESSION, state.syncId)) {
                        is Fetched.Found -> apply(state, row, fetched, templateKeys)
                        is Fetched.Deleted -> deletedElsewhere(state, row)
                        // The server lost it: a copy here goes up again with the next push.
                        Fetched.Missing -> if (row == null) {
                            dao.deleteState(state.kind, state.syncId)
                        } else {
                            dao.upsertState(
                                state.copy(remoteRevision = 0, baseRevision = 0, baseHash = null, conflict = false)
                            )
                        }
                    }
                } catch (e: SyncException) {
                    if (fail(e)) return
                }
            }
        }

        /** Delete [row] here without a tombstone: the server has deleted it already. */
        private suspend fun deletedElsewhere(state: SyncStateEntity, row: SessionEntity?) {
            dao.deleteState(state.kind, state.syncId)
            if (row != null) repository.deleteSession(row.id)
        }

        private suspend fun apply(
            state: SyncStateEntity,
            row: SessionEntity?,
            fetched: Fetched.Found,
            templateKeys: Map<String, String>
        ) {
            val manifest = try {
                SessionManifests.decode(fetched.manifest.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw SyncException("Unreadable session ${state.syncId} from the sync server", code = 422, cause = e)
            }
            if (manifest.schema > SessionManifests.SCHEMA) {
                throw SyncException("\"${manifest.title}\" needs a newer version of ConvoScribe", code = 422)
            }
            // Recordings this device already holds stay where they are.
            val inPlace = buildSet {
                val audio = row?.audioPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return@buildSet
                hashes.ref(audio)?.sha256?.takeIf { it == manifest.audio?.sha256 }?.let(::add)
                hashes.ref(row.cleanAudioFile())?.sha256?.takeIf { it == manifest.cleanAudio?.sha256 }?.let(::add)
            }
            val id = repository.applyManifest(
                manifest,
                updatedAt = fetched.changedAt,
                files = emptyMap(),
                inPlace = inPlace,
                allowMissing = true
            ) { ref -> ref.key ?: ref.user?.let(templateKeys::get) }
            val audio = manifest.audio?.takeIf { it.sha256 !in inPlace }
            val clean = manifest.cleanAudio?.takeIf { it.sha256 !in inPlace }
            val applied = state.copy(
                baseRevision = fetched.revision,
                remoteRevision = maxOf(state.remoteRevision, fetched.revision),
                remoteDeleted = false,
                conflict = false,
                excluded = false,
                pendingHash = null,
                pendingSince = null,
                lastError = null,
                audioSha = audio?.sha256,
                audioSize = audio?.size,
                cleanSha = clean?.sha256,
                cleanSize = clean?.size
            )
            // The hash of the session as stored here, so a difference in
            // how this install encodes it never counts as a change.
            val local = db.sessionDao().get(id) ?: return
            dao.upsertState(applied.copy(baseHash = snapshot(local, applied)?.manifest?.let(SessionManifests::hash)))
        }

        /**
         * Fetch the recordings downloaded sessions wait for, resuming
         * interrupted downloads, and put each in place once its hash
         * checks out.
         */
        private suspend fun downloadRecordings() {
            val pending = dao.pendingAudio(SyncKinds.SESSION).filterNot { it.excluded }
            if (pending.isEmpty()) return
            if (isMetered() && !settings.settings.value.syncAudioOnMobileData) {
                audioWaiting = true
                return
            }
            var attached = false
            try {
                for (state in pending) {
                    for (ref in listOfNotNull(state.pendingAudio, state.pendingClean)) {
                        if (isRecording()) {
                            deferred = true
                            return
                        }
                        try {
                            if (download(state.syncId, ref)) attached = true
                        } catch (e: SyncException) {
                            if (fail(e)) return
                        }
                    }
                }
            } finally {
                if (attached) onRecordingAttached()
            }
        }

        /** Download [ref] for session [syncId] and put it in place; false when the session no longer wants it. */
        private suspend fun download(syncId: String, ref: SessionManifest.FileRef): Boolean {
            val part = File(downloadDir, "${ref.sha256}.part")
            try {
                ResumableFetch(
                    http
                ).fetch(client.blobUrl(ref.sha256), part, ref.size, "recording", client.authHeaders) {}
            } catch (e: ModelDownloadException) {
                throw SyncException(e.message ?: "Download failed", code = if (e.transient) null else 404, cause = e)
            }
            if (SessionManifests.fileRef(part) != ref) {
                part.delete()
                throw SyncException("A recording from the sync server does not match its hash", code = 422)
            }
            return repository.attachRecording(syncId, ref, part).also { if (!it) part.delete() }
        }
    }

    private companion object {
        /** Change feed entries read per request. */
        const val FEED_PAGE = 200
    }
}
