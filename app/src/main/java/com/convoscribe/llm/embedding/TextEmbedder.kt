package com.convoscribe.llm.embedding

import java.io.Closeable
import kotlin.math.sqrt

/**
 * A text embedding model (issue #61): text in, a unit-length vector out,
 * so that texts meaning the same thing (also across English and French)
 * get vectors with a high dot product. Questions and passages are
 * embedded separately because models such as EmbeddingGemma prompt them
 * differently.
 *
 * Vectors are only comparable within one [modelId]; stored vectors carry
 * it so a different engine never mixes with them. Blocking and CPU-heavy:
 * call off the main thread. One instance may be used from one coroutine
 * at a time.
 */
interface TextEmbedder : Closeable {

    /** Identifies the model and its settings (prompts, [dimensions]); stored with each vector. */
    val modelId: String

    /** Length of every vector this embedder returns. */
    val dimensions: Int

    /** The vector of a question. */
    fun embedQuery(text: String): FloatArray

    /** The vector of each of [texts], in the same order. */
    fun embedPassages(texts: List<String>): List<FloatArray>
}

/**
 * Builds a [TextEmbedder] without the caller depending on the engine,
 * like [com.convoscribe.audio.AudioCleanerFactory].
 */
interface TextEmbedderFactory {

    /** Whether the model is installed, so [create] can succeed. */
    fun isAvailable(): Boolean

    /** The [TextEmbedder.modelId] that [create] would return, also when not installed. */
    fun modelId(): String

    /** A loaded embedder; throws [IllegalStateException] when the model is not installed. */
    fun create(): TextEmbedder
}

/** Vector helpers shared by the embedders and the ranking. */
object Vectors {

    /** The first [dimensions] values of [vector], scaled to unit length. */
    fun truncateAndNormalise(vector: FloatArray, dimensions: Int): FloatArray {
        val out = vector.copyOf(minOf(dimensions, vector.size))
        var sum = 0.0
        for (v in out) sum += v * v
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) for (i in out.indices) out[i] /= norm
        return out
    }

    /** Dot product of two unit vectors, their cosine similarity; 0 when their lengths differ. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
