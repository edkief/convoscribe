package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases the sync server's port of these rules is tested with (#84,
 * `server/src/lib/token-aligner.ts`), so an edit on the web keeps giving what
 * the same edit gives here.
 */
class TokenAlignerCasesTest {

    private val cases: JsonObject =
        Json.parseToJsonElement(File("../server/src/lib/token-aligner.cases.json").readText()).jsonObject

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.ms(name: String) = getValue(name).jsonPrimitive.long

    private fun tokens(element: JsonElement): List<TimedToken> = element.jsonArray.map {
        val token = it.jsonObject
        TimedToken(token.str("text"), token.ms("ms"))
    }

    private fun group(name: String): List<JsonObject> = cases.getValue(name).jsonArray.map { it.jsonObject }

    @Test
    fun theFixtureHasCases() {
        assertTrue(group("align").isNotEmpty() && group("clean").isNotEmpty() && group("editText").isNotEmpty())
    }

    @Test
    fun align() {
        for (case in group("align")) {
            assertEquals(
                case.str("name"),
                tokens(case.getValue("expected")),
                TokenAligner.align(tokens(case.getValue("oldTokens")), case.str("newText"), case.ms("startMs"), case.ms("endMs"))
            )
        }
    }

    @Test
    fun clean() {
        for (case in group("clean")) {
            assertEquals(case.str("input"), case.str("expected"), TranscriptEditor.clean(case.str("input")))
        }
    }

    @Test
    fun editText() {
        for (case in group("editText")) {
            val line = case.getValue("line").jsonObject
            val row = SegmentEntity(
                sessionId = 1,
                startMs = line.ms("startMs"),
                endMs = line.ms("endMs"),
                text = line.str("text"),
                tokens = TokenTimingCodec.encode(tokens(line.getValue("tokens")))
            )
            val edited = TranscriptEditor.editText(row, case.str("text"))
            val expected = case.getValue("expected")
            if (expected is JsonNull) {
                assertNull(case.str("name"), edited)
            } else {
                val want = expected.jsonObject
                assertEquals(case.str("name"), want.str("text"), edited!!.text)
                assertEquals(case.str("name"), tokens(want.getValue("tokens")), TokenTimingCodec.decode(edited.tokens))
                assertTrue(case.str("name"), edited.textEdited)
            }
        }
    }
}
