package com.convoscribe.data

import com.convoscribe.asr.TimedToken

/**
 * Keeps a transcript line's word timings when the user edits its
 * text (issue #26), so playback still highlights the word being said.
 *
 * The old tokens are grouped into words (a new word at each word-start
 * token, as [com.convoscribe.ui.session.PlaybackSync] does) and lined
 * up with the words of the new text by a longest common subsequence
 * on a loose key (lower case, letters and digits only), so a fixed
 * capital or comma still counts as the same word. A matched word keeps
 * its start time; a new or changed word gets a time spread evenly
 * between its timed neighbours, or the line's start and end.
 *
 * The result has one token per whitespace-separated word of the new
 * text, so the text and the tokens always line up word for word.
 */
object TokenAligner {

    private val WORD = Regex("\\S+")

    /**
     * Tokens for [newText] re-timed from [oldTokens] within
     * `[startMs, endMs]`; empty when [oldTokens] is empty (the line
     * had no timings, so it is only highlighted as a whole).
     */
    fun align(oldTokens: List<TimedToken>, newText: String, startMs: Long, endMs: Long): List<TimedToken> {
        if (oldTokens.isEmpty()) return emptyList()
        val newWords = WORD.findAll(newText).map { it.value }.toList()
        if (newWords.isEmpty()) return emptyList()
        val oldWords = words(oldTokens)
        val oldKeys = oldWords.map { word -> key(word.joinToString("") { it.text }) }
        val newKeys = newWords.map(::key)

        val times = arrayOfNulls<Long>(newWords.size)
        for ((oldIndex, newIndex) in commonSubsequence(oldKeys, newKeys)) {
            times[newIndex] = oldWords[oldIndex].first().ms
        }
        fillGaps(times, startMs, maxOf(startMs, endMs))
        return newWords.mapIndexed { i, word -> TimedToken(" $word", times[i]!!) }
    }

    /** [tokens] grouped into words: a new word starts at each word-start token. */
    fun words(tokens: List<TimedToken>): List<List<TimedToken>> {
        val words = mutableListOf<MutableList<TimedToken>>()
        for (token in tokens) {
            if (words.isEmpty() || token.isWordStart) words += mutableListOf(token) else words.last() += token
        }
        return words
    }

    /**
     * Character offsets in [text] where its whitespace-separated words
     * start, for snapping a split to a word boundary.
     */
    fun wordStarts(text: String): List<Int> = WORD.findAll(text).map { it.range.first }.toList()

    /** Lower case, letters and digits only; punctuation-only words keep their characters. */
    private fun key(word: String): String {
        val cleaned = word.replace('▁', ' ').trim()
        val letters = cleaned.filter { it.isLetterOrDigit() }.lowercase()
        return letters.ifEmpty { cleaned.lowercase() }
    }

    /** Index pairs (old, new) of one longest common subsequence of [a] and [b], in order. */
    private fun commonSubsequence(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lengths[i][j] = if (a[i] == b[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    pairs += i to j
                    i++
                    j++
                }
                lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    /**
     * Give each untimed word a time between its timed neighbours. A run
     * at the start of the line begins at [startMs]; any other run starts
     * after the timed word before it. Matched times are already in order
     * (a common subsequence keeps it), so the result never goes backwards.
     */
    private fun fillGaps(times: Array<Long?>, startMs: Long, endMs: Long) {
        var i = 0
        while (i < times.size) {
            if (times[i] != null) {
                i++
                continue
            }
            val runStart = i
            while (i < times.size && times[i] == null) i++
            val count = i - runStart
            val atLineStart = runStart == 0
            val left = if (atLineStart) startMs else times[runStart - 1]!!
            val right = maxOf(left, if (i < times.size) times[i]!! else endMs)
            val slots = if (atLineStart) count else count + 1
            for (k in 0 until count) {
                val step = if (atLineStart) k else k + 1
                times[runStart + k] = left + (right - left) * step / slots
            }
        }
    }
}
