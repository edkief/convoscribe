package com.convoscribe.llm.embedding

import com.convoscribe.data.SearchText

/**
 * A [TextEmbedder] for tests: one dimension per concept in [concepts],
 * and a text's vector counts the concept words it contains, so two
 * texts sharing no word but naming the same concept ("budget" and "how
 * much we can spend") come out close. Words are folded as the search
 * index folds them. A text naming no concept gets a vector on its own
 * axis, far from everything.
 */
class FakeTextEmbedder(
    private val concepts: List<Set<String>> = DEFAULT_CONCEPTS,
    override val modelId: String = "fake/1"
) : TextEmbedder {

    override val dimensions: Int = concepts.size + 1

    var queries: Int = 0
        private set
    var passages: Int = 0
        private set
    var closed: Boolean = false
        private set

    /** Throw from [embedQuery] when set, as a broken model would. */
    var failQueries: Boolean = false

    override fun embedQuery(text: String): FloatArray {
        if (failQueries) error("fake embedder failure")
        queries++
        return vector(text)
    }

    override fun embedPassages(texts: List<String>): List<FloatArray> {
        passages += texts.size
        return texts.map(::vector)
    }

    override fun close() {
        closed = true
    }

    private fun vector(text: String): FloatArray {
        val folded = " " + SearchText.fold(text).replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
        val raw = FloatArray(dimensions)
        concepts.forEachIndexed { i, words -> raw[i] = words.count { folded.contains(" $it ") }.toFloat() }
        if (raw.all { it == 0f }) raw[concepts.size] = 1f
        return Vectors.truncateAndNormalise(raw, dimensions)
    }

    companion object {
        val DEFAULT_CONCEPTS: List<Set<String>> = listOf(
            setOf("budget", "spend", "money", "euros", "cost", "afford", "depenser", "argent"),
            setOf("knee", "genou", "leg", "swelling", "jambe"),
            setOf("beta", "release", "launch", "ship", "sortie"),
            setOf("holiday", "vacation", "trip", "vacances", "voyage")
        )
    }
}

/** A [TextEmbedderFactory] over one [embedder], available unless [available] is false. */
class FakeTextEmbedderFactory(
    val embedder: FakeTextEmbedder = FakeTextEmbedder(),
    var available: Boolean = true
) : TextEmbedderFactory {
    var created: Int = 0
        private set

    override fun isAvailable(): Boolean = available
    override fun modelId(): String = embedder.modelId
    override fun create(): TextEmbedder {
        check(available) { "Model not installed: fake" }
        created++
        return embedder
    }
}
