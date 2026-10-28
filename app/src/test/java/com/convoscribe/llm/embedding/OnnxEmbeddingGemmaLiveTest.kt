package com.convoscribe.llm.embedding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [OnnxEmbeddingGemma] with the real model on the host's onnxruntime
 * (issue #61). Skipped unless `CONVOSCRIBE_EMBEDDINGGEMMA_DIR` names a
 * directory holding `model_q4.onnx`, `model_q4.onnx_data` and
 * `tokenizer.model`. Prints the similarities, which set
 * [com.convoscribe.llm.SemanticRanking.MIN_COSINE].
 */
class OnnxEmbeddingGemmaLiveTest {

    @Test
    fun paraphrasesScoreAboveUnrelatedText() {
        val dir = System.getenv("CONVOSCRIBE_EMBEDDINGGEMMA_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "model_q4.onnx").isFile)
        OnnxEmbeddingGemma(File(dir, "model_q4.onnx"), File(dir, "tokenizer.model")).use { gemma ->
            val question = gemma.embedQuery("How much money can we spend on the offsite?")
            val passages = listOf(
                "Marc: the budget for the offsite is capped at ten thousand euros.",
                "Marc : on ne peut pas dépasser dix mille euros pour le séminaire.",
                "Dr Martin: the swelling should go down, keep the leg raised.",
                "We moved the release of the beta to next quarter."
            )
            val started = System.nanoTime()
            val vectors = gemma.embedPassages(passages)
            val ms = (System.nanoTime() - started) / 1_000_000.0 / passages.size
            val scores = vectors.map { Vectors.cosine(question, it) }
            println("EmbeddingGemma cosines ${scores.map { "%.3f".format(it) }}, %.0f ms per passage".format(ms))

            assertEquals(OnnxEmbeddingGemma.DIMENSIONS, question.size)
            assertEquals(1.0, Vectors.cosine(question, question), 1e-4)
            assertTrue("EN paraphrase above unrelated", scores[0] > scores[2] + 0.15 && scores[0] > scores[3] + 0.15)
            assertTrue("FR paraphrase above unrelated", scores[1] > scores[2] + 0.15 && scores[1] > scores[3] + 0.15)
        }
    }
}
