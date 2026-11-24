package com.convoscribe.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperGreedyDecoderTest {

    private class Call(val token: Int, val position: Int, val openSlots: List<Int>)

    /** A decoder whose next token is [script]`[n]` at step `n`, recording each call. */
    private class Scripted(private val script: (Int) -> Int) : WhisperDecoderStep {
        val calls = mutableListOf<Call>()

        override fun step(token: Int, position: Int, mask: FloatArray): FloatArray {
            val open = mask.indices.filter { mask[it] == 0f }
            require(mask.filterIndexed { i, _ -> i !in open }.all { it == WhisperGreedyDecoder.MASK_NEG })
            calls += Call(token, position, open)
            return FloatArray(51_866).also { it[script(position)] = 1f }
        }
    }

    private val prompt = intArrayOf(WhisperTokens.SOT, 50259, WhisperTokens.TRANSCRIBE, WhisperTokens.NO_TIMESTAMPS)

    @Test
    fun feedsThePrompt_thenItsOwnPredictions_untilEndOfText() {
        val predicted = listOf(400, 401, 402, WhisperTokens.EOT)
        val step = Scripted { n -> if (n < 3) 7 else predicted[n - 3] }

        val out = WhisperGreedyDecoder.decode(prompt, step)

        assertEquals(listOf(400, 401, 402), out)
        assertEquals(listOf(prompt[0], prompt[1], prompt[2], prompt[3], 400, 401, 402), step.calls.map { it.token })
        assertEquals((0..6).toList(), step.calls.map { it.position })
    }

    @Test
    fun opensOneMaskSlotPerStep_fromTheEnd() {
        val step = Scripted { n -> if (n < 5) 10 else WhisperTokens.EOT }

        WhisperGreedyDecoder.decode(prompt, step)

        step.calls.forEachIndexed { n, call ->
            assertEquals((199 - n..199).toList(), call.openSlots)
        }
    }

    @Test
    fun stopsAfter199Steps_withoutEndOfText() {
        val step = Scripted { 42 }

        val out = WhisperGreedyDecoder.decode(prompt, step)

        assertEquals(199, step.calls.size)
        // Steps 3..198 each predict a token.
        assertEquals(196, out.size)
        assertEquals(198, step.calls.last().position)
    }

    @Test
    fun ignoresPredictionsDuringThePrompt() {
        // Even if the model would end during the prompt, the prompt is fed whole.
        val step = Scripted { n -> if (n < 3) WhisperTokens.EOT else if (n == 3) 9 else WhisperTokens.EOT }

        assertEquals(listOf(9), WhisperGreedyDecoder.decode(prompt, step))
        assertEquals(5, step.calls.size)
    }
}
