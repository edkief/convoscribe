package com.convoscribe.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database holding the five entity families that survive an
 * app restart: sessions, transcript segments, speaker labels,
 * LLM chat messages, and (v2) enrolled speakers. The current
 * version is 4; the generated JSON for every version is committed
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
 * [Converters] is registered so the enum columns ([SessionStatus],
 * [MessageKind], [MessageRole]) round-trip as their `name`.
 */
@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerEntity::class,
        ChatMessageEntity::class,
        EnrolledSpeakerEntity::class
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4)
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun segmentDao(): SegmentDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun enrolledSpeakerDao(): EnrolledSpeakerDao

    companion object {
        const val NAME: String = "convoscribe.db"
    }
}
