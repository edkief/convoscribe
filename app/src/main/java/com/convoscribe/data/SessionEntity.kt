package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded conversation. [audioPath] points at
 * `filesDir/recordings/<id>.wav`; the file is created by the
 * recording service and removed by [SessionRepository.deleteSession].
 *
 * [status] is the lifecycle marker (see [SessionStatus]); the UI
 * uses it to decide whether to show "Diarizing…" or a final speaker
 * list. [durationMs] is filled in when the recording stops; until
 * then it stays at 0.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val language: String,
    val audioPath: String = "",
    val status: SessionStatus,
    /**
     * Speaker count chosen for this session ("Re-identify speakers"):
     * `null` follows Settings, `-1` is automatic, `1..10` fixed.
     * New sessions always store the count chosen before Start;
     * `null` only remains on sessions from before issue #6.
     */
    val numSpeakers: Int? = null,
    /**
     * Enrolled people expected in this conversation, one name per
     * line (see [ParticipantsCodec]); only they are considered when
     * matching known voices. `null` means anyone.
     */
    val participants: String? = null,
    /**
     * ASR engine (`"cpu"` / `"npu"`) and model bundle id that produced
     * the transcript; `null` on sessions recorded before this was
     * kept. A continuation with a different engine or model stores
     * both, joined by `" + "`.
     */
    val asrEngine: String? = null,
    val asrModel: String? = null,
    /**
     * The transcript has been edited by hand (issue #26): a line's
     * text or speaker changed, or lines merged, split or deleted.
     * Re-transcribing would lose that, so it asks first. Cleared when
     * the transcript is replaced.
     */
    @ColumnInfo(defaultValue = "0")
    val transcriptEdited: Boolean = false
)
