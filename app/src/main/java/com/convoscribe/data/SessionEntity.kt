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
    val transcriptEdited: Boolean = false,
    /**
     * Key of the summary template chosen for this conversation (issue
     * #27, see [com.convoscribe.llm.SummaryTemplates]); `null` on
     * sessions from before templates, which use the current default.
     */
    val summaryTemplate: String? = null,
    /**
     * Id of the noise cleanup model that cleaned this session's audio
     * (issue #37), or `null` when it is not cleaned. When set, the
     * cleaned recording sits next to the original at
     * [cleanAudioFile], sample-aligned with it, and the transcript was
     * made from it. A session cleaned live keeps cleaning when continued.
     */
    val cleanedWith: String? = null,
    /**
     * Where [title] came from (issue #32), one of [TitleSource]: the
     * date-based default, typed by the user (or a preset), or written
     * by the LLM after a summary. Only a default title is replaced
     * automatically.
     */
    @ColumnInfo(defaultValue = TitleSource.DEFAULT)
    val titleSource: String = TitleSource.DEFAULT,
    /**
     * The title an automatic title replaced, so it can be restored;
     * `null` unless [titleSource] is [TitleSource.AUTO].
     */
    val previousTitle: String? = null
)

/** Values of [SessionEntity.titleSource] (issue #32). */
object TitleSource {
    /** `"Recording yyyy-MM-dd HH:mm"`; replaced by an automatic title. */
    const val DEFAULT: String = "default"

    /** Typed by the user, from a preset or an imported file's name; never replaced. */
    const val USER: String = "user"

    /** Written by the LLM after a summary; the previous title can be restored. */
    const val AUTO: String = "auto"
}

/**
 * Where [audioPath]'s cleaned copy lives (issue #37):
 * `<id>.clean.wav` next to `<id>.wav`.
 */
fun cleanAudioPathFor(audioPath: String): String = audioPath.removeSuffix(".wav") + ".clean.wav"

/** This session's cleaned recording; it exists only while [SessionEntity.cleanedWith] is set. */
fun SessionEntity.cleanAudioFile(): java.io.File = java.io.File(cleanAudioPathFor(audioPath))
