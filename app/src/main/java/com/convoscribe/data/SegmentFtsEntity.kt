package com.convoscribe.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * Full-text index over [SegmentEntity.text] (issue #24), searched by
 * [SearchDao.findSegments]. It is an external-content FTS4 table: the
 * text stays in `segments` only, and the triggers Room generates for
 * [Fts4.contentEntity] keep the index in step with every insert,
 * update and delete there, including the cascade when a session is
 * deleted. Row ids are the segments' ids.
 *
 * The `unicode61` tokenizer folds case and strips diacritics, so
 * "reunion" finds "Réunion". FTS5 is not available in Android's
 * SQLite, hence FTS4.
 */
@Fts4(contentEntity = SegmentEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "segments_fts")
data class SegmentFtsEntity(
    val text: String
)
