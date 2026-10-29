package com.convoscribe.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Room database holding the entity families that survive an
 * app restart: sessions, transcript segments, speaker labels,
 * LLM chat messages, (v2) enrolled speakers, (v5) a full-text
 * index over transcript lines, (v7) summary templates and (v8)
 * marks, (v12) tags and presets, (v14) the *Ask across sessions* history,
 * (v15) semantic search and (v16) stable session ids. The current version
 * is 16; the generated JSON for every version is committed
 * under `app/schemas/` so each auto-migration can be diffed
 * against it.
 *
 * The 1 → 2 migration adds the `enrolled_speakers` table only —
 * no existing table is touched — and Room generates the matching
 * `CREATE TABLE` SQL at first launch after the upgrade. The
 * migration is automatic because the diff is purely additive;
 * later migrations that change a column will need a hand-written
 * spec.
 *
 * The 2 → 3 migration (issue #1) is automatic too: nullable
 * `segments.tokens` (per-token timings) and `sessions.numSpeakers`
 * (per-session speaker count) columns, and the `enrolled_speakers`
 * name index made non-unique so a person can keep several voice
 * samples.
 *
 * The 3 → 4 migration (issue #6) is automatic: nullable
 * `sessions.participants`, `sessions.asrEngine` and
 * `sessions.asrModel` columns. Existing rows keep their language and
 * speaker count, and show the engine as unknown.
 *
 * The 4 → 5 migration (issue #24) adds the `segments_fts` FTS4
 * index ([SegmentFtsEntity]) and its sync triggers; [Migration4To5]
 * then fills it from the existing segments.
 *
 * The 5 → 6 migration (issue #26) is automatic: `segments.textEdited`,
 * `segments.speakerEdited` and `sessions.transcriptEdited`, all
 * defaulting to `0`, so existing transcripts read as unedited.
 *
 * The 6 → 7 migration (issue #27) is automatic: the
 * `summary_templates` table, and nullable `sessions.summaryTemplate`
 * and `chat_messages.templateName`, so existing sessions follow the
 * default template and existing summaries show no template name.
 *
 * The 7 → 8 migration (issue #30) is automatic: the `marks` table
 * only, so existing sessions simply have no marks.
 *
 * The 8 → 9 migration (issue #37) is automatic: nullable
 * `sessions.cleanedWith`, so existing sessions read as not cleaned.
 *
 * The 9 → 10 migration (issue #28) is automatic: nullable
 * `chat_messages.coveredMs` and `chat_messages.totalMs`, so existing
 * answers read as based on the whole transcript.
 *
 * The 10 → 11 migration (issue #48) is automatic: nullable
 * `marks.expandedNote` and `marks.expandedAt`, and
 * `marks.expandedStale` defaulting to `0`, so existing marks have no
 * full note yet.
 *
 * The 11 → 12 migration (issue #32) adds the `tags`, `session_tags`
 * and `presets` tables, `sessions.titleSource` defaulting to
 * `"default"` and nullable `sessions.previousTitle`. A title renamed
 * before the upgrade cannot be told from a default one by its column,
 * so [Migration11To12] marks every title that is not
 * `"Recording yyyy-MM-dd HH:mm"` as typed by the user; only default
 * titles are ever replaced by an automatic one.
 *
 * The 13 → 14 migration (issue #49) is automatic: the
 * `global_chat_messages` table only, so *Ask across sessions* starts
 * with no history.
 *
 * The 14 → 15 migration (issue #61) is automatic: the `passage_vectors`
 * and `session_index_state` tables, so every session starts unindexed
 * and is indexed once the semantic search model is installed, and a
 * nullable `global_chat_messages.semantic`, so earlier answers read as
 * found by words alone.
 *
 * The 15 → 16 migration (issue #25) is hand-written ([MIGRATION_15_16]):
 * `sessions.syncId`, unique, and `sessions.updatedAt`. Every existing
 * session needs its own random id before the unique index can exist,
 * which an automatic migration cannot do; `updatedAt` starts at
 * `createdAt`.
 *
 * The 16 → 17 migration (issue #68) is automatic: the `sync_state`,
 * `sync_tombstones` and `file_hashes` tables, all empty, so every
 * session starts out waiting to be uploaded.
 *
 * [Converters] is registered so the enum columns ([SessionStatus],
 * [MessageKind], [MessageRole]) round-trip as their `name`.
 */
@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerEntity::class,
        ChatMessageEntity::class,
        EnrolledSpeakerEntity::class,
        SegmentFtsEntity::class,
        SummaryTemplateEntity::class,
        MarkEntity::class,
        TagEntity::class,
        SessionTagEntity::class,
        PresetEntity::class,
        GlobalChatMessageEntity::class,
        PassageVectorEntity::class,
        SessionIndexStateEntity::class,
        SyncStateEntity::class,
        SyncTombstoneEntity::class,
        FileHashEntity::class
    ],
    version = 17,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = Migration4To5::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12, spec = Migration11To12::class),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 16, to = 17)
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun segmentDao(): SegmentDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun enrolledSpeakerDao(): EnrolledSpeakerDao
    abstract fun searchDao(): SearchDao
    abstract fun summaryTemplateDao(): SummaryTemplateDao
    abstract fun markDao(): MarkDao
    abstract fun tagDao(): TagDao
    abstract fun presetDao(): PresetDao
    abstract fun globalChatMessageDao(): GlobalChatMessageDao
    abstract fun semanticIndexDao(): SemanticIndexDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME: String = "convoscribe.db"
    }
}

/**
 * The new FTS index starts empty and its triggers only see later
 * writes, so index the transcripts recorded before the upgrade.
 */
class Migration4To5 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO segments_fts(segments_fts) VALUES('rebuild')")
    }
}

/**
 * Existing titles all read as `"default"` after the upgrade, but some
 * were typed by hand. Keep only the date-based ones as default, so an
 * automatic title never replaces something the user wrote; imported
 * sessions, titled after their file, count as typed too.
 */
class Migration11To12 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE sessions SET titleSource = '${TitleSource.USER}' WHERE title NOT GLOB " +
                "'Recording [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]'"
        )
    }
}

/**
 * Give every existing session a random [SessionEntity.syncId] and an
 * [SessionEntity.updatedAt] of its `createdAt`, then make `syncId`
 * unique (issue #25). Registered on the database builder.
 */
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE sessions SET updatedAt = createdAt")
        val ids = buildList {
            db.query("SELECT id FROM sessions").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        for (id in ids) {
            db.execSQL("UPDATE sessions SET syncId = ? WHERE id = ?", arrayOf<Any>(UUID.randomUUID().toString(), id))
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_syncId ON sessions (syncId)")
    }
}
