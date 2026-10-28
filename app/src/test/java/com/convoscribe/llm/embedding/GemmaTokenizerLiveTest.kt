package com.convoscribe.llm.embedding

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [GemmaTokenizer] on the real `tokenizer.model` (issue #61), against
 * ids from the reference tokenizer (`tokenizers` on the same model's
 * `tokenizer.json`) in `embedding/gemma-tokens.json`. Skipped unless
 * `CONVOSCRIBE_EMBEDDINGGEMMA_DIR` names a directory holding the
 * model's files.
 */
class GemmaTokenizerLiveTest {

    @Test
    fun matchesTheReferenceTokenizer() {
        val dir = System.getenv("CONVOSCRIBE_EMBEDDINGGEMMA_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "tokenizer.model").isFile)
        val tokenizer = GemmaTokenizer.load(File(dir, "tokenizer.model"))
        val cases = Json.parseToJsonElement(
            javaClass.getResource("/embedding/gemma-tokens.json")!!.readText()
        ).jsonArray

        for (case in cases) {
            val text = case.jsonObject.getValue("text").jsonPrimitive.content
            val expected = case.jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.int }
            assertEquals("ids of ${text.take(40)}", expected, tokenizer.encode(text).toList())
        }
    }
}
