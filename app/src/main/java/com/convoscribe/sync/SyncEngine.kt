package com.convoscribe.sync

import android.util.Log
import androidx.room.withTransaction
import com.convoscribe.TAG
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SyncKinds
import com.convoscribe.data.SyncStateEntity
import com.convoscribe.data.SyncTombstoneEntity
import com.convoscribe.data.cleanAudioFile
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** How a [SyncEngine.run] ended, which tells the job whether to retry. */
enum class SyncOutcome {
    /** Everything that could be uploaded was. */
    DONE,

    /** Sync is off or has no server. */
    DISABLED,

    /** A recording is running; try again once it stops. */
    DEFERRED,

    /** The network or the server failed; try again later. */
    RETRY
}

/**
 * Uploads sessions to the sync server (issue #68, the first slice of
 * #67): deletes first ([SyncTombstoneEntity]), then every session whose
 * manifest hashes to something other than what the server last
 * accepted, recordings before the manifest that names them.
 *
 * It only reads sessions, and writes only its own bookkeeping, so a
 * failing server never changes anything else on the device. A session
 * still recording or identifying its speakers waits for the next run,
 * and the run stops as soon as a recording starts. One run at a time.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val status: SyncStatusStore,
    private val isRecording: () -> Boolean,
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
            Run(client, BlobUploader(client, dao, partBytes)).execute()
        } finally {
            status.update { it.copy(running = false) }
        }
    }

    private inner class Run(private val client: SyncClient, private val uploader: BlobUploader) {
        private var waiting = 0
        private var error: String? = null
        private var offline = false
        private var deferred = false

        suspend fun execute(): SyncOutcome {
            pushTombstones()
            if (!offline) pushSessions()
            status.update { previous ->
                when {
                    offline -> previous.copy(lastError = error)
                    deferred -> previous.copy(waiting = maxOf(previous.waiting, waiting))
                    else -> previous.copy(
                        waiting = waiting,
                        lastError = error,
                        lastSyncedAt = if (error == null) clock() else previous.lastSyncedAt
                    )
                }
            }
            return when {
                offline || error != null -> SyncOutcome.RETRY
                deferred -> SyncOutcome.DEFERRED
                else -> SyncOutcome.DONE
            }
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
                    when (client.deleteDocument(tombstone.kind, tombstone.syncId, tombstone.baseRevision)) {
                        is PushResult.Accepted, PushResult.NotFound -> dao.deleteTombstone(
                            tombstone.kind,
                            tombstone.syncId
                        )
                        // Changed on another device since; two-way sync (#69) settles it.
                        else -> waiting++
                    }
                } catch (e: SyncException) {
                    if (fail(e)) return
                }
            }
        }

        /**
         * A session the server has that is gone here without a tombstone
         * (deleted while its upload was finishing, or the process died
         * mid-delete) is deleted on the server too.
         */
        private suspend fun orphansToTombstones() {
            for (state in dao.states(SyncKinds.SESSION)) {
                if (state.baseRevision == 0L || db.sessionDao().getBySyncId(state.syncId) != null) continue
                dao.upsertTombstone(SyncTombstoneEntity(state.kind, state.syncId, state.baseRevision, clock()))
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
            val audio = row.audioPath.takeIf { it.isNotBlank() }?.let(::File)
            val clean = audio?.let { row.cleanAudioFile() }?.takeIf { row.cleanedWith != null }
            val refs = listOfNotNull(
                audio,
                clean
            ).mapNotNull { file -> hashes.ref(file)?.let { file.path to it } }.toMap()
            val snapshot = repository.snapshot(row.id) { file -> refs[file.path] } ?: return true
            val manifest = snapshot.manifest
            val hash = SessionManifests.hash(manifest)

            val state = dao.state(SyncKinds.SESSION, manifest.syncId)
                ?: SyncStateEntity(SyncKinds.SESSION, manifest.syncId)
            if (hash == state.baseHash) return true
            if (state.conflict) return false
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
                    val file = snapshot.files[ref.sha256] ?: continue
                    uploader.ensure(ref, file)
                }
                val result = client.putDocument(
                    kind = SyncKinds.SESSION,
                    syncId = manifest.syncId,
                    manifest = SessionManifests.encode(manifest).toByteArray(Charsets.UTF_8),
                    manifestHash = hash,
                    baseRevision = state.baseRevision,
                    durationMs = manifest.durationMs,
                    changedAt = maxOf(snapshot.updatedAt, pending.pendingSince ?: 0L)
                )
                return when (result) {
                    is PushResult.Accepted -> {
                        accepted(pending, result.meta.revision, hash)
                        true
                    }
                    is PushResult.Conflict -> {
                        dao.upsertState(pending.copy(conflict = true, lastError = CONFLICT_MESSAGE))
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
            if (db.sessionDao().getBySyncId(pending.syncId) == null) {
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
    }

    private companion object {
        const val CONFLICT_MESSAGE = "Changed on another device; waits for two-way sync"
    }
}
