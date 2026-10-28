package com.convoscribe.llm.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import java.io.File
import java.nio.LongBuffer

/**
 * EmbeddingGemma 300m (issue #61) through onnxruntime on the CPU.
 *
 * Texts get the model card's retrieval prompts, are cut to
 * [MAX_TOKENS], and are run in small batches of similar length so
 * little time goes on padding. The model's `sentence_embedding` (768
 * values, trained so that a prefix still works) is cut to [DIMENSIONS]
 * and made unit length.
 */
class OnnxEmbeddingGemma(modelFile: File, tokenizerFile: File, threads: Int = defaultThreads()) : TextEmbedder {

    override val modelId: String = MODEL_ID
    override val dimensions: Int = DIMENSIONS

    private val tokenizer = GemmaTokenizer.load(tokenizerFile)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modelFile.absolutePath, options)
    }

    override fun embedQuery(text: String): FloatArray =
        runEncoded(listOf(tokenizer.encode(QUERY_PROMPT + text, MAX_TOKENS))).single()

    override fun embedPassages(texts: List<String>): List<FloatArray> {
        val encoded = texts.map { tokenizer.encode(PASSAGE_PROMPT + it, MAX_TOKENS) }
        val out = arrayOfNulls<FloatArray>(texts.size)
        encoded.indices.sortedBy { encoded[it].size }.chunked(BATCH).forEach { batch ->
            runEncoded(batch.map { encoded[it] }).forEachIndexed { i, vector -> out[batch[i]] = vector }
        }
        return out.map { it!! }
    }

    /** The vectors of token sequences [batch], padded to the longest. */
    private fun runEncoded(batch: List<IntArray>): List<FloatArray> {
        val length = batch.maxOf { it.size }
        val ids = LongArray(batch.size * length) { GemmaTokenizer.PAD.toLong() }
        val mask = LongArray(batch.size * length)
        batch.forEachIndexed { row, tokens ->
            tokens.forEachIndexed { col, id ->
                ids[row * length + col] = id.toLong()
                mask[row * length + col] = 1L
            }
        }
        val shape = longArrayOf(batch.size.toLong(), length.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                session.run(
                    mapOf("input_ids" to idTensor, "attention_mask" to maskTensor),
                    setOf(OUTPUT)
                ).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val vectors = result.get(OUTPUT).get().value as Array<FloatArray>
                    return vectors.map { Vectors.truncateAndNormalise(it, DIMENSIONS) }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val DIMENSIONS: Int = 256

        /** Bundle and vector length: vectors cut to another length are not comparable. */
        val MODEL_ID: String = "${ModelCatalog.TEXT_EMBEDDING_GEMMA.id}/$DIMENSIONS"

        /** Longest input, prompt included; passages are made to fit well within it. */
        const val MAX_TOKENS: Int = 512

        private const val BATCH = 4
        private const val OUTPUT = "sentence_embedding"

        // From the EmbeddingGemma model card, for retrieval.
        const val QUERY_PROMPT: String = "task: search result | query: "
        const val PASSAGE_PROMPT: String = "title: none | text: "

        /** Half the cores (the big ones on a phone), 1 to 4. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}

/** [TextEmbedderFactory] for [ModelCatalog.TEXT_EMBEDDING_GEMMA] in [store]. */
class EmbeddingGemmaFactory(private val store: ModelStore) : TextEmbedderFactory {

    private val bundle = ModelCatalog.TEXT_EMBEDDING_GEMMA

    override fun isAvailable(): Boolean = store.isInstalled(bundle)

    override fun modelId(): String = OnnxEmbeddingGemma.MODEL_ID

    override fun create(): TextEmbedder {
        check(store.isInstalled(bundle)) { "Model not installed: ${bundle.id}" }
        return OnnxEmbeddingGemma(store.fileFor(bundle, "model_q4.onnx"), store.fileFor(bundle, "tokenizer.model"))
    }
}
