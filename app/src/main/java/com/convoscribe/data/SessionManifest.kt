package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import com.convoscribe.llm.SummaryTemplates
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One session in a portable form (issue #25): everything needed to
 * rebuild it on another install or device, and nothing that only
 * makes sense in this database (row ids, `audioPath`). Backups store
 * one per session, and sync (#67) sends the same document.
 *
 * Child rows are listed in a fixed order and [status] is never a
 * short-lived state, so an unchanged session always encodes to the
 * same bytes and [SessionManifests.hash] only changes when the
 * session does. Recordings are referenced by content ([FileRef]) and
 * travel as separate files.
 *
 * [summaryTemplate] names a user template by its name, since its key
 * holds a local id ([SummaryTemplates.userKey]).
 *
 * [actionItems] (issue #31) is left out of the encoding while it is
 * empty, so a session without action items keeps the bytes, and the
 * hash, it had before they existed and is not pushed again for them.
 * Deleted items travel too, so another device's next summary does not
 * bring them back.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionManifest(
    val schema: Int = SessionManifests.SCHEMA,
    val syncId: String,
    val title: String,
    val titleSource: String,
    val previousTitle: String? = null,
    val createdAt: Long,
    val durationMs: Long,
    val language: String,
    val status: String,
    val numSpeakers: Int? = null,
    val participants: List<String>? = null,
    val asrEngine: String? = null,
    val asrModel: String? = null,
    val transcriptEdited: Boolean = false,
    val summaryTemplate: TemplateRef? = null,
    val cleanedWith: String? = null,
    val diarizedWith: String? = null,
    val diarizationStepMs: Int? = null,
    val diarizationMs: Long? = null,
    val audio: FileRef? = null,
    val cleanAudio: FileRef? = null,
    val lines: List<Line> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val messages: List<Message> = emptyList(),
    val marks: List<Mark> = emptyList(),
    val tags: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val actionItems: List<ActionItem> = emptyList()
) {
    /** A recording, by the SHA-256 of its bytes (lower-case hex) and its size. */
    @Serializable
    data class FileRef(val sha256: String, val size: Long)

    /** A built-in template by [key], or a user template by [user] name. */
    @Serializable
    data class TemplateRef(val key: String? = null, val user: String? = null)

    @Serializable
    data class Line(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speakerIndex: Int? = null,
        val tokens: List<Token> = emptyList(),
        val textEdited: Boolean = false,
        val speakerEdited: Boolean = false
    )

    @Serializable
    data class Token(val ms: Long, val text: String)

    @Serializable
    data class Speaker(val index: Int, val label: String)

    @Serializable
    data class Message(
        val kind: String,
        val role: String,
        val content: String,
        val createdAt: Long,
        val templateName: String? = null,
        val coveredMs: Long? = null,
        val totalMs: Long? = null
    )

    @Serializable
    data class Mark(
        val atMs: Long,
        val note: String? = null,
        val createdAt: Long,
        val expandedNote: String? = null,
        val expandedAt: Long? = null,
        val expandedStale: Boolean = false
    )

    @Serializable
    data class ActionItem(
        val text: String,
        val extractedText: String,
        val owner: String? = null,
        val dueDate: String? = null,
        val atMs: Long? = null,
        val done: Boolean = false,
        val edited: Boolean = false,
        val deleted: Boolean = false,
        val createdAt: Long
    )
}

/**
 * A session as it leaves the device (issue #25): its [manifest], when
 * it last changed, and the recordings its [SessionManifest.FileRef]s
 * name, by SHA-256.
 */
class SessionSnapshot(
    val manifest: SessionManifest,
    val updatedAt: Long,
    val files: Map<String, File>
)

/** Building, encoding and hashing [SessionManifest]s. */
object SessionManifests {
    /** Version of [SessionManifest]; a reader refuses a newer one. */
    const val SCHEMA: Int = 1

    /** Encodes every field, defaults included, so the bytes are canonical. */
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * The manifest of [session] and its rows. [userTemplateNames] maps
     * a user template's id to its name; [audio] and [cleanAudio]
     * describe the recordings, `null` when they are missing. The
     * [actionItems] are listed in their order; their position is
     * where they come in the list.
     */
    fun build(
        session: SessionEntity,
        lines: List<SegmentEntity>,
        speakers: List<SpeakerEntity>,
        messages: List<ChatMessageEntity>,
        marks: List<MarkEntity>,
        tags: List<String>,
        userTemplateNames: Map<Long, String>,
        audio: SessionManifest.FileRef?,
        cleanAudio: SessionManifest.FileRef?,
        actionItems: List<ActionItemEntity> = emptyList()
    ): SessionManifest = SessionManifest(
        syncId = session.syncId,
        title = session.title,
        titleSource = session.titleSource,
        previousTitle = session.previousTitle,
        createdAt = session.createdAt,
        durationMs = session.durationMs,
        language = session.language,
        status = portableStatus(session.status).name,
        numSpeakers = session.numSpeakers,
        participants = ParticipantsCodec.decode(session.participants),
        asrEngine = session.asrEngine,
        asrModel = session.asrModel,
        transcriptEdited = session.transcriptEdited,
        summaryTemplate = session.summaryTemplate?.let { templateRef(it, userTemplateNames) },
        cleanedWith = session.cleanedWith,
        diarizedWith = session.diarizedWith,
        diarizationStepMs = session.diarizationStepMs,
        diarizationMs = session.diarizationMs,
        audio = audio,
        cleanAudio = cleanAudio.takeIf { session.cleanedWith != null },
        lines = lines.sortedWith(compareBy({ it.startMs }, { it.id })).map { line ->
            SessionManifest.Line(
                startMs = line.startMs,
                endMs = line.endMs,
                text = line.text,
                speakerIndex = line.speakerIndex,
                tokens = TokenTimingCodec.decode(line.tokens).map { SessionManifest.Token(it.ms, it.text) },
                textEdited = line.textEdited,
                speakerEdited = line.speakerEdited
            )
        },
        speakers = speakers.sortedBy { it.speakerIndex }.map { SessionManifest.Speaker(it.speakerIndex, it.label) },
        messages = messages.sortedWith(compareBy({ it.createdAt }, { it.id })).map { message ->
            SessionManifest.Message(
                kind = message.kind.name,
                role = message.role.name,
                content = message.content,
                createdAt = message.createdAt,
                templateName = message.templateName,
                coveredMs = message.coveredMs,
                totalMs = message.totalMs
            )
        },
        marks = marks.sortedWith(compareBy({ it.atMs }, { it.id })).map { mark ->
            SessionManifest.Mark(
                atMs = mark.atMs,
                note = mark.note,
                createdAt = mark.createdAt,
                expandedNote = mark.expandedNote,
                expandedAt = mark.expandedAt,
                expandedStale = mark.expandedStale
            )
        },
        tags = tags.sortedBy { TagNames.key(it) },
        actionItems = actionItems.sortedWith(compareBy({ it.position }, { it.id })).map { item ->
            SessionManifest.ActionItem(
                text = item.text,
                extractedText = item.extractedText,
                owner = item.owner,
                dueDate = item.dueDate,
                atMs = item.atMs,
                done = item.done,
                edited = item.edited,
                deleted = item.deleted,
                createdAt = item.createdAt
            )
        }
    )

    /**
     * A session caught identifying its speakers is stored as waiting
     * for them, as crash recovery would leave it, so the run starting
     * or ending does not count as a change.
     */
    fun portableStatus(status: SessionStatus): SessionStatus =
        if (status == SessionStatus.DIARIZING) SessionStatus.DIARIZATION_PENDING else status

    /**
     * [status] as stored here. A session cannot arrive still recording,
     * so that reads as transcribed; an unknown status as waiting for
     * speakers.
     */
    fun status(status: String): SessionStatus =
        when (val known = SessionStatus.entries.firstOrNull { it.name == status }) {
            null -> SessionStatus.DIARIZATION_PENDING
            SessionStatus.RECORDING -> SessionStatus.TRANSCRIBED
            else -> portableStatus(known)
        }

    fun encode(manifest: SessionManifest): String = json.encodeToString(SessionManifest.serializer(), manifest)

    fun decode(text: String): SessionManifest = json.decodeFromString(SessionManifest.serializer(), text)

    /** SHA-256 of [manifest]'s canonical encoding, lower-case hex. */
    fun hash(manifest: SessionManifest): String = sha256(encode(manifest).toByteArray(Charsets.UTF_8))

    /** [file]'s [SessionManifest.FileRef], or `null` when it does not exist. */
    fun fileRef(file: File): SessionManifest.FileRef? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return SessionManifest.FileRef(digest.digest().toHex(), file.length())
    }

    /** The tokens of [line] as the app keeps them. */
    fun tokens(line: SessionManifest.Line): List<TimedToken> = line.tokens.map { TimedToken(it.text, it.ms) }

    /** [key] as a portable reference: a user template by its name, `null` when it no longer exists. */
    fun templateRef(key: String, userTemplateNames: Map<Long, String>): SessionManifest.TemplateRef? {
        val userId = SummaryTemplates.userId(key) ?: return SessionManifest.TemplateRef(key = key)
        // A deleted user template already resolves to the default.
        return userTemplateNames[userId]?.let { SessionManifest.TemplateRef(user = it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val BUFFER_SIZE = 64 * 1024
}
