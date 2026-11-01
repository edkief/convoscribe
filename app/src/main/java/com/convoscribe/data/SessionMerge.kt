package com.convoscribe.data

/**
 * Three-way merge of a session changed in two places (issue #90): what
 * each side changed since [merge]'s `base` is kept, and when both sides
 * changed one value the later change wins (higher `changedAt`, then
 * higher manifest hash, as the server's conflict rule).
 *
 * Title, tags, speakers, marks, Q&A and action items merge independently.
 * The recording and its transcript go together: a side that continued or
 * re-transcribed the recording ([rewrote]) wins them whole, the longer
 * recording if both did. Otherwise lines merge one by one, matched on
 * their times, with the line structure of the side that split, merged or
 * deleted lines. A re-identification of the speakers renumbers them, so
 * its speakers and line speakers win whole too. Edits that cannot carry
 * over are listed in [Result.dropped].
 *
 * The summary is one rolling text, so it merges as one value. A delete of
 * a mark, tag or Q&A message sticks. An action item's `deleted` sticks,
 * but an item a new summary removed stays if the other side checked or
 * edited it, and a new summary's item that repeats one already listed
 * is left out ([ActionItemMerge.same]).
 *
 * Without a base (a session changed here before this device kept one)
 * every difference counts as changed on both sides.
 */
object SessionMerge {

    /** The merged session, and a description of each edit that could not carry over. */
    class Result(val manifest: SessionManifest, val dropped: List<String> = emptyList())

    /** Compares unequal to everything, for a value with no base. */
    private val NO_BASE = Any()

    fun merge(
        base: SessionManifest?,
        local: SessionManifest,
        remote: SessionManifest,
        localChangedAt: Long,
        remoteChangedAt: Long
    ): Result {
        if (local == remote || remote == base) return Result(local)
        if (local == base) return Result(remote)
        val localLater = when {
            localChangedAt != remoteChangedAt -> localChangedAt > remoteChangedAt
            else -> SessionManifests.hash(local) > SessionManifests.hash(remote)
        }
        return Merger(base, local, remote, localLater).merge()
    }

    private class Merger(
        private val base: SessionManifest?,
        private val local: SessionManifest,
        private val remote: SessionManifest,
        private val localLater: Boolean
    ) {
        private val later = if (localLater) local else remote
        private val dropped = ArrayList<String>()

        /** What changed against [base]: the side that did, or the later if both did. */
        private fun <T> pick(base: Any?, local: T, remote: T): T = when {
            local == remote || remote == base -> local
            local == base -> remote
            localLater -> local
            else -> remote
        }

        private fun <T> field(get: (SessionManifest) -> T): T =
            pick(if (base != null) get(base) else NO_BASE, get(local), get(remote))

        fun merge(): Result {
            val recording = recordingWinner()
            val speakers = speakerSource(recording)
            val clean = if (recording != null) {
                recording.cleanedWith to recording.cleanAudio
            } else {
                field { it.cleanedWith to it.cleanAudio }
            }
            val title = field { Triple(it.title, it.titleSource, it.previousTitle) }
            // With no re-identification, both sides still have the base's.
            val run = DiarizationState(speakers ?: later)
            val manifest = SessionManifest(
                syncId = remote.syncId,
                title = title.first,
                titleSource = title.second,
                previousTitle = title.third,
                createdAt = field { it.createdAt },
                durationMs = recording?.durationMs ?: field { it.durationMs },
                language = field { it.language },
                status = run.status,
                numSpeakers = field { it.numSpeakers },
                participants = field { it.participants },
                asrEngine = if (recording != null) recording.asrEngine else field { it.asrEngine },
                asrModel = if (recording != null) recording.asrModel else field { it.asrModel },
                transcriptEdited = recording?.transcriptEdited ?: (local.transcriptEdited || remote.transcriptEdited),
                summaryTemplate = field { it.summaryTemplate },
                cleanedWith = clean.first,
                diarizedWith = run.diarizedWith,
                diarizationStepMs = run.stepMs,
                diarizationMs = run.ms,
                audio = if (recording != null) recording.audio else field { it.audio },
                cleanAudio = clean.second,
                lines = recording?.let { takeLines(it) } ?: mergeLines(speakers),
                speakers = speakers?.speakers ?: mergeSpeakers(),
                messages = mergeMessages(),
                marks = mergeMarks(),
                tags = mergeTags(),
                actionItems = mergeActionItems()
            )
            return Result(manifest, dropped)
        }

        /** The side whose recording and transcript win whole, if a side [rewrote] them. */
        private fun recordingWinner(): SessionManifest? {
            val localRewrote = rewrote(local)
            val remoteRewrote = rewrote(remote)
            return when {
                localRewrote && remoteRewrote -> when {
                    local.durationMs != remote.durationMs -> if (local.durationMs > remote.durationMs) local else remote
                    else -> later
                }
                localRewrote -> local
                remoteRewrote -> remote
                else -> null
            }
        }

        /**
         * Whether [side] continued or re-transcribed the recording.
         * Removing a cleaned copy, or losing a file, is not: the
         * transcript stays what it was.
         */
        private fun rewrote(side: SessionManifest): Boolean {
            val base = base ?: return true
            return (side.audio != null && side.audio != base.audio) ||
                side.durationMs != base.durationMs ||
                side.asrEngine != base.asrEngine ||
                side.asrModel != base.asrModel ||
                (side.cleanedWith != null && side.cleanedWith != base.cleanedWith) ||
                (side.cleanAudio != null && side.cleanAudio != base.cleanAudio) ||
                (base.transcriptEdited && !side.transcriptEdited)
        }

        /**
         * The side whose speaker numbering wins: one that identified the
         * speakers again, or the side that wins the recording if either did.
         */
        private fun speakerSource(recording: SessionManifest?): SessionManifest? {
            val localRan = reidentified(local)
            val remoteRan = reidentified(remote)
            return when {
                !localRan && !remoteRan -> null
                recording != null -> recording
                localRan && remoteRan -> later
                localRan -> local
                else -> remote
            }
        }

        private fun reidentified(side: SessionManifest): Boolean =
            DiarizationState(side) != base?.let(::DiarizationState)

        private fun takeLines(winner: SessionManifest): List<SessionManifest.Line> {
            val loser = if (winner === local) remote else local
            val before = byKey(base?.lines.orEmpty(), ::lineKey)
            for ((key, line) in byKey(loser.lines, ::lineKey)) {
                if (line != before[key] && line !in winner.lines) dropped += describe(line)
            }
            return winner.lines
        }

        private fun mergeLines(speakers: SessionManifest?): List<SessionManifest.Line> {
            val before = byKey(base?.lines.orEmpty(), ::lineKey)
            val localLines = byKey(local.lines, ::lineKey)
            val remoteLines = byKey(remote.lines, ::lineKey)
            val localMoved = localLines.keys != before.keys
            val remoteMoved = remoteLines.keys != before.keys
            val structure = when {
                localMoved && remoteMoved -> localLater
                else -> localMoved
            }
            val (lines, others) = if (structure) localLines to remoteLines else remoteLines to localLines
            val speakerLines = speakers?.let { byKey(it.lines, ::lineKey) }
            for ((key, line) in others) {
                if (key !in lines && line != before[key]) dropped += describe(line)
            }
            return lines.map { (key, line) ->
                val other = others[key] ?: return@map line
                val was = before[key]
                val l = if (structure) line else other
                val r = if (structure) other else line
                val text = pick(was?.let(::lineText) ?: NO_BASE, lineText(l), lineText(r))
                val who = speakerLines?.get(key)?.let(::lineSpeaker)
                    ?: pick(was?.let(::lineSpeaker) ?: NO_BASE, lineSpeaker(l), lineSpeaker(r))
                line.copy(
                    text = text.first,
                    tokens = text.second,
                    textEdited = text.third,
                    speakerIndex = who.first,
                    speakerEdited = who.second
                )
            }
        }

        private fun mergeSpeakers(): List<SessionManifest.Speaker> =
            keyed(base?.speakers, local.speakers, remote.speakers, { it.index }) { was, l, r ->
                pick(was ?: NO_BASE, l, r)
            }.sortedBy { it.index }

        private fun mergeMessages(): List<SessionManifest.Message> {
            val summary = field { side -> side.messages.filter { it.kind == MessageKind.SUMMARY.name } }
            fun qa(side: SessionManifest) = side.messages.filter { it.kind != MessageKind.SUMMARY.name }
            val rounds = keyed(
                base?.let(::qa),
                qa(local),
                qa(remote),
                { Triple(it.kind, it.role, it.createdAt) }
            ) { was, l, r -> pick(was ?: NO_BASE, l, r) }
            return (summary + rounds).sortedBy { it.createdAt }
        }

        private fun mergeMarks(): List<SessionManifest.Mark> =
            keyed(base?.marks, local.marks, remote.marks, { it.createdAt to it.atMs }) { was, l, r ->
                val note = pick(if (was != null) was.note else NO_BASE, l.note, r.note)
                val full = pick(was?.let(::markExpansion) ?: NO_BASE, markExpansion(l), markExpansion(r))
                l.copy(note = note, expandedNote = full.first, expandedAt = full.second, expandedStale = full.third)
            }.sortedBy { it.atMs }

        private fun mergeTags(): List<String> = keyed(base?.tags, local.tags, remote.tags, TagNames::key) { was, l, r ->
            pick(was ?: NO_BASE, l, r)
        }.sortedBy { TagNames.key(it) }

        private fun mergeActionItems(): List<SessionManifest.ActionItem> {
            val key = { item: SessionManifest.ActionItem -> item.createdAt to item.extractedText }
            val merged = keyed(
                base?.actionItems,
                local.actionItems,
                remote.actionItems,
                key,
                editBeatsRemoval = true
            ) { was, l, r -> mergeItem(was, l, r) }
            // A new summary's items, on one side only: the rest are already settled.
            val shared = byKey(local.actionItems, key).keys.intersect(byKey(remote.actionItems, key).keys)
            val before = byKey(base?.actionItems.orEmpty(), key).keys
            val keys = byKey(merged, key)
            val settled = keys.filter { (k, item) -> k in shared || k in before || item.isTouched }.keys
            val seen = keys.filterKeys { it in settled }.values.flatMap { listOf(it.text, it.extractedText) }
                .toMutableList()
            return keys.filter { (k, item) ->
                if (k in settled) return@filter true
                if (seen.any { ActionItemMerge.same(it, item.text) }) return@filter false
                seen += item.text
                true
            }.values.toList()
        }

        private val SessionManifest.ActionItem.isTouched: Boolean get() = done || edited || deleted

        private fun mergeItem(
            was: SessionManifest.ActionItem?,
            l: SessionManifest.ActionItem,
            r: SessionManifest.ActionItem
        ): SessionManifest.ActionItem {
            fun <T> item(get: (SessionManifest.ActionItem) -> T): T =
                pick(if (was != null) get(was) else NO_BASE, get(l), get(r))
            return l.copy(
                text = item { it.text },
                owner = item { it.owner },
                dueDate = item { it.dueDate },
                atMs = item { it.atMs },
                done = item { it.done },
                edited = l.edited || r.edited,
                deleted = l.deleted || r.deleted
            )
        }

        /**
         * [local] and [remote] merged by [key], remote's order then what
         * only local has. An item on both sides goes through [both]. An
         * item on one side only was added there if the base lacks it, and
         * removed on the other side if the base has it: gone, unless
         * [editBeatsRemoval] and this side changed it.
         */
        private fun <T, K> keyed(
            base: List<T>?,
            local: List<T>,
            remote: List<T>,
            key: (T) -> K,
            editBeatsRemoval: Boolean = false,
            both: (T?, T, T) -> T
        ): List<T> {
            val before = byKey(base.orEmpty(), key)
            val localItems = byKey(local, key)
            val remoteItems = byKey(remote, key)
            fun alone(k: Pair<K, Int>, item: T): T? {
                val was = before[k] ?: return item
                return item.takeIf { editBeatsRemoval && it != was }
            }
            val fromRemote = remoteItems.mapNotNull { (k, r) ->
                val l = localItems[k]
                if (l != null) both(before[k], l, r) else alone(k, r)
            }
            val fromLocal = localItems.filterKeys { it !in remoteItems }.mapNotNull { (k, l) -> alone(k, l) }
            return fromRemote + fromLocal
        }

        /** [items] by key and how many earlier items share it, in order. */
        private fun <T, K> byKey(items: List<T>, key: (T) -> K): Map<Pair<K, Int>, T> {
            val seen = HashMap<K, Int>()
            return items.associateByTo(LinkedHashMap()) { item ->
                val k = key(item)
                val occurrence = seen[k] ?: 0
                seen[k] = occurrence + 1
                k to occurrence
            }
        }

        private fun lineKey(line: SessionManifest.Line) = line.startMs to line.endMs

        private fun lineText(line: SessionManifest.Line) = Triple(line.text, line.tokens, line.textEdited)

        private fun lineSpeaker(line: SessionManifest.Line) = line.speakerIndex to line.speakerEdited

        private fun markExpansion(mark: SessionManifest.Mark) =
            Triple(mark.expandedNote, mark.expandedAt, mark.expandedStale)

        private fun describe(line: SessionManifest.Line) = "line ${line.startMs}-${line.endMs} ms"
    }

    /** What identifying the speakers, or re-transcribing, leaves on a session. */
    private data class DiarizationState(
        val status: String,
        val diarizedWith: String?,
        val stepMs: Int?,
        val ms: Long?
    ) {
        constructor(side: SessionManifest) : this(
            side.status,
            side.diarizedWith,
            side.diarizationStepMs,
            side.diarizationMs
        )
    }
}
