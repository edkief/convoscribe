package com.convoscribe.asr

/**
 * One decoder pass of Qualcomm's Whisper NPU build (issue #159): feed
 * [token] at [position] with [mask] (the `attention_mask`, `0` for the
 * filled cache slots and [WhisperGreedyDecoder.MASK_NEG] elsewhere) and
 * return the next-token logits. The implementation carries the self
 * attention caches from one call to the next.
 */
fun interface WhisperDecoderStep {
    fun step(token: Int, position: Int, mask: FloatArray): FloatArray
}

/**
 * Greedy decoding for one 30 s window, as Qualcomm AI Hub's reference
 * app does it (`hf_whisper/app.py`, `_transcribe_single_chunk`), with a
 * forced prompt instead of letting the model pick the language:
 *
 *  - step `n` feeds the `n`-th prompt token, then each predicted token;
 *  - before step `n` the mask slot `MAX_TOKENS − n − 1` is opened (the
 *    model's self caches fill from the end);
 *  - prediction starts after the last prompt token and stops at
 *    end-of-text or after `MAX_TOKENS − 1` steps.
 *
 * Returns the predicted tokens, without the prompt or end-of-text.
 */
object WhisperGreedyDecoder {

    /** Decoder length the NPU build was compiled for. */
    const val MAX_TOKENS = 200

    /** The reference app's mask value for closed slots. */
    const val MASK_NEG = -100f

    fun decode(prompt: IntArray, step: WhisperDecoderStep): List<Int> {
        require(prompt.isNotEmpty() && prompt.size < MAX_TOKENS)
        val mask = FloatArray(MAX_TOKENS) { MASK_NEG }
        val out = mutableListOf<Int>()
        var token = prompt[0]
        for (n in 0 until MAX_TOKENS - 1) {
            mask[MAX_TOKENS - n - 1] = 0f
            val logits = step.step(token, n, mask)
            if (n < prompt.size - 1) {
                token = prompt[n + 1]
                continue
            }
            val next = argmax(logits)
            if (next == WhisperTokens.EOT) break
            out += next
            token = next
        }
        return out
    }

    internal fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }
}
