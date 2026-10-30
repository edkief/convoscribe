package com.convoscribe.sync

import androidx.room.withTransaction
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.LibraryManifests
import com.convoscribe.data.MAX_SAMPLES_PER_PERSON
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.PresetEntity
import com.convoscribe.data.PresetManifest
import com.convoscribe.data.SummaryTemplateEntity
import com.convoscribe.data.SyncKinds
import com.convoscribe.data.TemplateManifest
import com.convoscribe.data.VoiceManifest
import com.convoscribe.data.ensure
import com.convoscribe.llm.SummaryTemplates
import java.util.Base64
import kotlinx.serialization.KSerializer

/**
 * One kind of library item that syncs as a document per item (issue
 * #70): known-voice samples, presets and summary templates. [SyncEngine]
 * does the pushing and downloading; this says how an item of [kind]
 * leaves and enters the database.
 */
abstract class LibraryKind<M>(val kind: String, val serializer: KSerializer<M>) {
    /** A local item's manifest and when it last changed. */
    class Local<M>(val manifest: M, val changedAt: Long)

    /** Every local item, by sync id. */
    abstract suspend fun local(): Map<String, Local<M>>

    abstract suspend fun exists(syncId: String): Boolean

    /** Store [manifest], last changed at [changedAt], in place of the item with its sync id. */
    abstract suspend fun apply(manifest: M, changedAt: Long)

    abstract suspend fun delete(syncId: String)

    /** Give the local item [from] the sync id [to]. */
    abstract suspend fun rekey(from: String, to: String)

    /** Whether [a] and [b] are the same item but for their ids and when they were made. */
    abstract fun same(a: M, b: M): Boolean

    abstract fun schemaOf(manifest: M): Int

    /** Called once a run's downloads of this kind are applied, with what was applied. */
    open suspend fun afterApply(applied: List<M>) {}

    fun encode(manifest: M): String = LibraryManifests.encode(serializer, manifest)

    fun decode(text: String): M = LibraryManifests.decode(serializer, text)

    fun hash(manifest: M): String = LibraryManifests.hash(serializer, manifest)

    companion object {
        /** The kinds in the order a run syncs them: templates before the presets that name them. */
        fun all(db: AppDatabase): List<LibraryKind<*>> = listOf(TemplateKind(db), PresetKind(db), VoiceKind(db))
    }
}

/** Summary templates the user wrote; the most recent edit wins. */
private class TemplateKind(db: AppDatabase) :
    LibraryKind<TemplateManifest>(SyncKinds.TEMPLATE, TemplateManifest.serializer()) {
    private val dao = db.summaryTemplateDao()

    override suspend fun local() = dao.getAll().associate {
        it.syncId to Local(LibraryManifests.template(it), it.updatedAt)
    }

    override suspend fun exists(syncId: String) = dao.getBySyncId(syncId) != null

    override suspend fun apply(manifest: TemplateManifest, changedAt: Long) {
        val existing = dao.getBySyncId(manifest.syncId)
        if (existing != null) {
            dao.update(existing.id, manifest.name, manifest.body, changedAt)
        } else {
            dao.insert(
                SummaryTemplateEntity(
                    name = manifest.name,
                    body = manifest.body,
                    createdAt = manifest.createdAt,
                    syncId = manifest.syncId,
                    updatedAt = changedAt
                )
            )
        }
    }

    override suspend fun delete(syncId: String) {
        dao.getBySyncId(syncId)?.let { dao.delete(it.id) }
    }

    override suspend fun rekey(from: String, to: String) = dao.rekey(from, to)

    override fun same(a: TemplateManifest, b: TemplateManifest) = a.name == b.name && a.body == b.body

    override fun schemaOf(manifest: TemplateManifest) = manifest.schema
}

/**
 * Presets, with tags by name (created when missing) and their template
 * by name; a template this device does not have reads as the default.
 */
private class PresetKind(private val db: AppDatabase) :
    LibraryKind<PresetManifest>(SyncKinds.PRESET, PresetManifest.serializer()) {
    private val dao = db.presetDao()

    override suspend fun local(): Map<String, Local<PresetManifest>> {
        val tagNames = db.tagDao().getAll().associate { it.id to it.name }
        val templateNames = db.summaryTemplateDao().getAll().associate { it.id to it.name }
        return dao.getAll().associate {
            it.syncId to Local(LibraryManifests.preset(it, tagNames, templateNames), it.updatedAt)
        }
    }

    override suspend fun exists(syncId: String) = dao.getBySyncId(syncId) != null

    override suspend fun apply(manifest: PresetManifest, changedAt: Long) = db.withTransaction {
        val templates = db.summaryTemplateDao().getAll()
        val template = manifest.summaryTemplate?.let { ref ->
            ref.key ?: templates.firstOrNull { it.name == ref.user }?.let { SummaryTemplates.userKey(it.id) }
        }
        val existing = dao.getBySyncId(manifest.syncId)
        val row = PresetEntity(
            id = existing?.id ?: 0,
            name = manifest.name,
            titlePattern = manifest.titlePattern,
            language = manifest.language,
            numSpeakers = manifest.numSpeakers,
            participants = ParticipantsCodec.encode(manifest.participants),
            tagIds = db.tagDao().ensure(manifest.tags, changedAt).joinToString("\n"),
            summaryTemplate = template ?: SummaryTemplates.MEETING_KEY,
            cleanAudio = manifest.cleanAudio,
            createdAt = manifest.createdAt,
            syncId = manifest.syncId,
            updatedAt = changedAt
        )
        if (existing != null) dao.update(row) else dao.insert(row)
        Unit
    }

    override suspend fun delete(syncId: String) {
        dao.getBySyncId(syncId)?.let { dao.delete(it.id) }
    }

    override suspend fun rekey(from: String, to: String) = dao.rekey(from, to)

    override fun same(a: PresetManifest, b: PresetManifest) =
        a.copy(syncId = "", createdAt = 0) == b.copy(syncId = "", createdAt = 0)

    override fun schemaOf(manifest: PresetManifest) = manifest.schema
}

/**
 * Known-voice samples, one document each, so samples added on two
 * devices both survive. Once downloads land, each person is pruned to
 * the newest [MAX_SAMPLES_PER_PERSON] samples, the same ones on every
 * device; the samples that go are then deleted everywhere.
 */
private class VoiceKind(db: AppDatabase) : LibraryKind<VoiceManifest>(SyncKinds.VOICE, VoiceManifest.serializer()) {
    private val dao = db.enrolledSpeakerDao()

    override suspend fun local() =
        dao.getAll().associate { it.syncId to Local(LibraryManifests.voice(it), it.createdAt) }

    override suspend fun exists(syncId: String) = dao.getBySyncId(syncId) != null

    override suspend fun apply(manifest: VoiceManifest, changedAt: Long) {
        // A sample is never edited: one already here is the same.
        if (dao.getBySyncId(manifest.syncId) != null) return
        dao.insert(
            EnrolledSpeakerEntity(
                name = manifest.name,
                embedding = Base64.getDecoder().decode(manifest.embedding),
                createdAt = manifest.createdAt,
                syncId = manifest.syncId
            )
        )
    }

    override suspend fun delete(syncId: String) {
        dao.getBySyncId(syncId)?.let { dao.delete(it.id) }
    }

    override suspend fun rekey(from: String, to: String) = dao.rekey(from, to)

    override fun same(a: VoiceManifest, b: VoiceManifest) = a.name == b.name && a.embedding == b.embedding

    override fun schemaOf(manifest: VoiceManifest) = manifest.schema

    override suspend fun afterApply(applied: List<VoiceManifest>) {
        for (name in applied.map { it.name }.toSet()) dao.pruneName(name, MAX_SAMPLES_PER_PERSON)
    }
}
