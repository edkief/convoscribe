package com.convoscribe.enginecheck

import com.convoscribe.llm.embedding.TextEmbedder
import java.io.File

/**
 * One timing of the semantic search model (issue #61): [loadMs] to load
 * it, [passageMs] per passage over [passages] transcript-sized passages,
 * [queryMs] for one question, and the app's memory in MB before loading
 * ([memoryBeforeMb]) and at its peak afterwards ([peakMemoryMb]), from
 * `/proc/self/status`; `null` where the system does not say. [error]
 * when it could not run.
 */
data class SemanticCheckResult(
    val loadMs: Long,
    val passageMs: Long,
    val queryMs: Long,
    val passages: Int,
    val memoryBeforeMb: Long? = null,
    val peakMemoryMb: Long? = null,
    val error: String? = null
)

/** Eight passages about as long as the index's, half English, half French. */
internal val SEMANTIC_CHECK_PASSAGES: List<String> = List(4) { i ->
    listOf(
        "Alice: so for the offsite we said the budget would stay under ten thousand euros, " +
            "travel included. Bob: and the venue confirmed the second week of June. " +
            "Alice: then we still need the catering quote before Friday, otherwise the price goes up again.",
        "Marc : pour le séminaire on avait dit qu'on ne dépasserait pas dix mille euros, voyage compris. " +
            "Julie : et la salle a confirmé la deuxième semaine de juin. Marc : il nous manque encore le devis " +
            "du traiteur avant vendredi, sinon le prix augmente encore."
    )[i % 2].repeat(3) + " ($i)"
} + List(4) { i ->
    listOf(
        "Dr Martin: the swelling in the knee should go down within a week if you keep the leg raised. " +
            "Patient: and can I still go running? Dr Martin: not before the MRI on Tuesday, then we will see.",
        "Équipe : on décale la sortie de la bêta au trimestre prochain, le temps de corriger la synchronisation. " +
            "Paul : il faudra prévenir les testeurs aujourd'hui pour qu'ils ne mettent pas à jour."
    )[i % 2].repeat(3) + " (${i + 4})"
}

/**
 * Load a model with [create], time [SEMANTIC_CHECK_PASSAGES] and one
 * question through it, then close it. An exception becomes
 * [SemanticCheckResult.error] instead of escaping.
 */
fun measureSemantic(
    create: () -> TextEmbedder,
    clock: () -> Long,
    memoryMb: (String) -> Long? = ::procStatusMb
): SemanticCheckResult {
    val before = memoryMb("VmRSS")
    var embedder: TextEmbedder? = null
    return try {
        val loadStart = clock()
        embedder = create()
        val loadMs = clock() - loadStart
        val passageStart = clock()
        embedder.embedPassages(SEMANTIC_CHECK_PASSAGES)
        val passageMs = (clock() - passageStart) / SEMANTIC_CHECK_PASSAGES.size
        val queryStart = clock()
        embedder.embedQuery("How much can we spend on the offsite?")
        val queryMs = clock() - queryStart
        SemanticCheckResult(loadMs, passageMs, queryMs, SEMANTIC_CHECK_PASSAGES.size, before, memoryMb("VmHWM"))
    } catch (t: Throwable) {
        SemanticCheckResult(0L, 0L, 0L, 0, before, null, error = t.message ?: t.toString())
    } finally {
        runCatching { embedder?.close() }
    }
}

/** A `kB` field of `/proc/self/status` (`VmRSS`, `VmHWM`) in MB, or `null`. */
internal fun procStatusMb(field: String, status: File = File("/proc/self/status")): Long? = runCatching {
    status.readLines().firstOrNull { it.startsWith("$field:") }
        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.div(1024)
}.getOrNull()
