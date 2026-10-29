package com.convoscribe.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Saved conversation options for a recurring meeting (issue #32):
 * the options the Record screen asks for, plus a [titlePattern] that
 * may hold `{date}` and `{time}`. [tags] are names; a tag deleted
 * since the preset was saved is simply gone from it. [syncId] is its
 * id on every device (issue #70); `null` for a new one gets a fresh id.
 */
data class Preset(
    val id: Long = 0,
    val name: String,
    val titlePattern: String = "",
    val language: String,
    val numSpeakers: Int = -1,
    val participants: List<String>? = null,
    val tags: List<String> = emptyList(),
    val summaryTemplate: String,
    val cleanAudio: Boolean = false,
    val syncId: String? = null
)

/** The user's presets (issue #32). */
interface PresetStore {

    /** Every preset, alphabetically. */
    fun observeAll(): Flow<List<Preset>>

    /** One preset, or `null` when [id] does not exist (any more). */
    suspend fun get(id: Long): Preset?

    /**
     * Save [preset]: a new one when its id is `0`, otherwise an update.
     * Its tags are created when missing. Returns the preset's id.
     */
    suspend fun save(preset: Preset): Long

    suspend fun delete(id: Long)

    companion object {
        /** Longest preset name. */
        const val NAME_MAX_LEN: Int = 40
    }
}

/** Room-backed [PresetStore]; tags are stored by id so renaming a tag carries over. */
class PresetRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) : PresetStore {
    private val dao = db.presetDao()
    private val tagDao = db.tagDao()

    override fun observeAll(): Flow<List<Preset>> = combine(dao.observeAll(), tagDao.observeAll()) { rows, tags ->
        val names = tags.associate { it.id to it.name }
        rows.map { it.toPreset(names) }
    }

    override suspend fun get(id: Long): Preset? {
        val row = dao.get(id) ?: return null
        return row.toPreset(tagDao.getAll().associate { it.id to it.name })
    }

    override suspend fun save(preset: Preset): Long = db.withTransaction {
        val now = clock()
        val row = PresetEntity(
            id = preset.id,
            name = preset.name.trim().take(PresetStore.NAME_MAX_LEN),
            titlePattern = preset.titlePattern.trim().take(SessionRepository.SESSION_TITLE_MAX_LEN),
            language = preset.language,
            numSpeakers = SessionRepository.normaliseSpeakerCount(preset.numSpeakers) ?: -1,
            participants = ParticipantsCodec.encode(preset.participants),
            tagIds = tagDao.ensure(preset.tags, now).joinToString("\n"),
            summaryTemplate = preset.summaryTemplate,
            cleanAudio = preset.cleanAudio,
            createdAt = now,
            syncId = preset.syncId ?: UUID.randomUUID().toString()
        )
        if (preset.id == 0L) {
            dao.insert(row)
        } else {
            val existing = dao.get(preset.id)
            dao.update(
                row.copy(
                    createdAt = existing?.createdAt ?: now,
                    syncId = existing?.syncId ?: row.syncId,
                    updatedAt = now
                )
            )
            preset.id
        }
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    private fun PresetEntity.toPreset(tagNames: Map<Long, String>): Preset = Preset(
        id = id,
        name = name,
        titlePattern = titlePattern,
        language = language,
        numSpeakers = numSpeakers,
        participants = ParticipantsCodec.decode(participants),
        tags = tagIds.split('\n').mapNotNull { it.toLongOrNull()?.let(tagNames::get) },
        summaryTemplate = summaryTemplate,
        cleanAudio = cleanAudio,
        syncId = syncId
    )
}
