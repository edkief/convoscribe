package com.convoscribe.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database holding the entity families that survive an
 * app restart: sessions, transcript segments, speaker labels,
 * LLM chat messages, (v2) enrolled speakers, (v5) a full-text
 * index over transcript lines, (v7) summary templates and (v8)
 * marks. The current version is 8; the generated JSON for every version is committed
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
        MarkEntity::class
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = Migration4To5::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)
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
