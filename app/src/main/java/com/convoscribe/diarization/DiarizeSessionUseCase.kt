package com.convoscribe.diarization

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.audio.WavReader
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.SegmentPiece
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TokenTimingCodec
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Run the diarization pass that turns a `TRANSCRIBED` session
 * into a `DIARIZED` one: read the WAV the recorder wrote, ask the
 * engine for the speaker turns, map them onto the persisted
 * transcript segments through [SpeakerAssigner], and write the
 * assignment back to the database in one transaction (see
 * [SessionRepository.applyDiarization]).
 *
 * Wiring lives at [com.convoscribe.AppContainer.diarizeSessionUseCase];
 * the post-record pipeline (`RecordingService` after `controller.stop()`)
 * and the crash-recovery boot path both go through this class so
 * a session only ever has one diarization rule.
 *
 * ## Threading
 *
 * The heavy work — disk I/O, native engine, in-memory clustering
 * — happens on [Dispatchers.Default] so the caller's coroutine
 * (typically the service's `Main.immediate` scope) is never
 * blocked. The status flips happen on the same dispatcher so the
 * `DIARIZING` ↔ `DIARIZED` / `DIARIZATION_FAILED` transitions are
 * linearised.
 *
 * ## Failure handling
 *
 * Any throwable except [CancellationException] is swallowed: the
 * session status is moved to [SessionStatus.DIARIZATION_FAILED]
 * and the exception is logged with `Log.w` so the recording
 * service can stop foreground cleanly and the user can still see
 * the transcript (they may retry from the session list). Embedding
 * failures specifically are logged with `Log.w` and the
 * diarization lands with the default `"Speaker N"` labels: a
 * crashed speaker model must not prevent the user from seeing the
 * transcript, only from seeing names.
 *
 * @property repository the [SessionRepository] used to flip status
 *   and persist the per-segment assignment.
 * @property diarizerFactory builds a fresh [Diarizer] for the
 *   duration of the call; the engine is closed in a `use { }`
 *   block so the native memory is released even on failure.
 * @property settings the user's settings (the [numSpeakers] and
 *   [clusterThreshold] overrides are forwarded to the engine,
 *   and [speakerMatchThreshold] gates the cosine matching).
 * @property embedderFactory builds a fresh [SpeakerEmbedder] for
 *   the per-speaker embedding pass. Invoked at most once; left
 *   to a sensible default (a no-op factory) so unit tests that
 *   don't care about embeddings can ignore the parameter.
 * @property enrolled speakers the user has enrolled with the
 *   v2 voice-sample flow. When empty, the embedder factory is
 *   never called (an empty `FloatArray` slice cannot be
 *   matched against anything).
 * @property readSamples how to load the WAV into a float array;
 *   defaults to [WavReader.readMono16k]. Injected so tests can
 *   skip the WAV header parsing and feed the engine a
 *   `FloatArray(16000)` directly.
 */
class DiarizeSessionUseCase(
    private val repository: SessionRepository,
    private val diarizerFactory: DiarizerFactory,
    private val settings: SettingsRepository,
    private val embedderFactory: SpeakerEmbedderFactory = SpeakerEmbedderFactory {
        error("Embedder factory not configured")
    },
    private val enrolled: EnrolledSpeakerStore = EmptyEnrolledStore,
    private val readSamples: (File) -> FloatArray = WavReader::readMono16k
) {

    /**
     * Diarize the session identified by [sessionId]. [onProgress] is
     * invoked with the 0..1 fraction the engine reports and is the
     * one observability hook for the UI ("Identifying speakers…
     * 42%"). Returns once the session is `DIARIZED` (success, or
     * zero segments) or `DIARIZATION_FAILED` (any other exception).
     *
     * A session whose transcript has zero segments skips the
     * engine entirely and lands on [SessionStatus.DIARIZED]; the
     * diarizer factory is not invoked, the WAV is not read, and
     * no speaker rows are written.
     *
     * When the user has at least one enrolled speaker, the use
     * case runs a second pass after diarization: [SpeakerSampleCollector]
     * builds one audio window per detected speaker, the embedder
     * turns each window into a vector, and [SpeakerMatcher]
     * matches them against the enrolled names. Speakers with a
     * confident match overwrite the default `"Speaker N"` label.
     * Any failure inside that pass is logged with `Log.w` and
     * the diarization lands with default labels so a broken
     * embedding model never drops the user's transcript.
     *
     * On success, an info-level line is written with the shared
     * [TAG] (`adb logcat -s ConvoScribe`) so the owner can confirm
     * in adb logcat that the post-record diarization step actually
     * ran. The line includes the session id, total wall-clock
     * duration, and the number of speakers the engine detected —
     * the three values the manual test checklist calls out as
     * worth recording.
     *
     * [numSpeakers], when given, is saved as the session's speaker
     * count first ("Re-identify speakers"). The count used is the
     * session's, else the Settings default.
     */
    suspend fun run(sessionId: Long, onProgress: (Float) -> Unit = {}, numSpeakers: Int? = null) {
        val startedNanos = System.nanoTime()
        try {
            if (numSpeakers != null) repository.setSessionSpeakerCount(sessionId, numSpeakers)
            repository.setStatus(sessionId, SessionStatus.DIARIZING)
            val session = repository.getSession(sessionId)
                ?: run {
                    Log.w(TAG, "session $sessionId disappeared before diarization")
                    return
                }
            val segments = repository.getSegments(sessionId)
            if (segments.isEmpty()) {
                // No transcript = nothing to label. Land on
                // DIARIZED directly so the UI shows a stable
                // status without a transient DIARIZATION_FAILED.
                Log.i(TAG, "session $sessionId has zero segments; skipping diarization")
                repository.applyDiarization(
                    sessionId = sessionId,
                    assignments = emptyMap(),
                    speakerCount = 0
                )
                return
            }
            val samples = withContext(Dispatchers.Default) {
                readSamples(File(session.audioPath))
            }
            val speakerSettings = settings.settings.value
            val turns = withContext(Dispatchers.Default) {
                diarizerFactory.create().use { diarizer ->
                    diarizer.diarize(
                        samples = samples,
                        numSpeakers = session.numSpeakers ?: speakerSettings.numSpeakers,
                        threshold = speakerSettings.clusterThreshold,
                        onProgress = onProgress
                    )
                }
            }
            // Split lines at speaker changes (where token times exist),
            // then number speakers by first appearance.
            val split = segments.map { segment ->
                segment to SegmentSplitter.split(
                    SplittableSegment(
                        id = segment.id,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        text = segment.text,
                        tokens = TokenTimingCodec.decode(segment.tokens)
                    ),
                    turns
                )
            }
            val rawToIndex = SpeakerAssigner.renumber(
                split.flatMap { (_, pieces) -> pieces }.sortedBy { it.startMs }.map { it.rawSpeaker }
            )
            val speakerCount = rawToIndex.size
            val assignments = mutableMapOf<Long, Int?>()
            val replacements = mutableMapOf<Long, List<SegmentPiece>>()
            for ((segment, pieces) in split) {
                if (pieces.size == 1) {
                    assignments[segment.id] = pieces.single().rawSpeaker?.let { rawToIndex[it] }
                } else {
                    replacements[segment.id] = pieces.map { piece ->
                        SegmentPiece(
                            startMs = piece.startMs,
                            endMs = piece.endMs,
                            text = piece.text,
                            tokens = piece.tokens,
                            speakerIndex = piece.rawSpeaker?.let { rawToIndex[it] }
                        )
                    }
                }
            }
            val enrolledLabels = computeLabels(
                sessionId = sessionId,
                samples = samples,
                turns = turns,
                rawToIndex = rawToIndex,
                speakerCount = speakerCount,
                settings = speakerSettings,
                participants = ParticipantsCodec.decode(session.participants)
            )
            // Names typed on an earlier diarization survive the re-run;
            // a match against an enrolled voice still wins.
            val carriedLabels = SpeakerLabelCarryOver.carry(
                previous = segments.map { LabelledSpan(it.startMs, it.endMs, it.speakerIndex) },
                previousLabels = repository.getSpeakers(sessionId).associate { it.speakerIndex to it.label },
                current = split.flatMap { (_, pieces) ->
                    pieces.map { LabelledSpan(it.startMs, it.endMs, it.rawSpeaker?.let { raw -> rawToIndex[raw] }) }
                }
            )
            val labels = carriedLabels + enrolledLabels
            repository.applyDiarization(
                sessionId = sessionId,
                assignments = assignments,
                speakerCount = speakerCount,
                labels = labels,
                replacements = replacements
            )
            val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
            Log.i(
                TAG,
                "diarization completed session=$sessionId durationMs=$durationMs" +
                    " speakers=$speakerCount splitLines=${replacements.size}"
            )
        } catch (ce: CancellationException) {
            // Cooperative cancellation: re-throw so structured
            // concurrency still sees the cancel and the service
            // shutdown path stays clean. Do not flip status to
            // DIARIZATION_FAILED — the recorder / recovery loop
            // will pick the session up again on next start.
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Diarization failed for session $sessionId", t)
            repository.setStatus(sessionId, SessionStatus.DIARIZATION_FAILED)
        }
    }

    /**
     * Run the optional speaker-embedding pass: collect audio
     * windows per detected speaker, embed them, and match the
     * resulting vectors against the user's enrolled speakers.
     *
     * Only the session's expected [participants] are matched
     * against (`null` = every enrolled person), so a voice is never
     * named after someone who was not there.
     *
     * Skips the entire pass when:
     *  * the session has zero speakers (no audio to embed);
     *  * no enrolled speaker is left to match against (none
     *    enrolled, or none of the participants) — building the
     *    embedder would just waste native memory.
     *
     * Embedding failures are caught and logged with `Log.w`
     * because they must not fail the diarization: a broken
     * speaker model is not a reason to lose the transcript.
     * The function returns an empty map in that case so the
     * repository falls back to its default `"Speaker N"` labels.
     */
    private suspend fun computeLabels(
        sessionId: Long,
        samples: FloatArray,
        turns: List<SpeakerTurn>,
        rawToIndex: Map<Int, Int>,
        speakerCount: Int,
        settings: com.convoscribe.settings.AppSettings,
        participants: List<String>?
    ): Map<Int, String> {
        if (speakerCount == 0) return emptyMap()
        val enrolledSpeakers = enrolled.getAll().filter { participants == null || it.name in participants }
        if (enrolledSpeakers.isEmpty()) return emptyMap()

        return try {
            val speakerWindows = withContext(Dispatchers.Default) {
                SpeakerSampleCollector.collect(
                    samples = samples,
                    turns = turns,
                    speakerMap = { raw -> rawToIndex[raw] }
                )
            }
            if (speakerWindows.isEmpty()) return emptyMap()
            val embeddings = withContext(Dispatchers.Default) {
                embedderFactory.create().use { embedder ->
                    speakerWindows.mapValues { (_, window) -> embedder.embed(window) }
                }
            }
            SpeakerMatcher.assignNames(
                embeddings = embeddings,
                enrolled = enrolledSpeakers,
                threshold = settings.speakerMatchThreshold
            )
        } catch (t: Throwable) {
            Log.w(TAG, "speaker embedding failed for session $sessionId", t)
            emptyMap()
        }
    }

    /**
     * Default [EnrolledSpeakerStore] used by callers that did
     * not pass one (the existing tests, for example). Returns
     * an empty list every time so the embedding pass short-
     * circuits and never instantiates an embedder.
     */
    private object EmptyEnrolledStore : EnrolledSpeakerStore {
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(
            emptyList<com.convoscribe.data.EnrolledSpeakerEntity>()
        )
        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = emptyList()
        override suspend fun save(name: String, embedding: FloatArray) {
            error("EmptyEnrolledStore is read-only")
        }

        @Suppress("unused")
        override suspend fun delete(id: Long) = Unit
    }
}
