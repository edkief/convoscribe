package com.convoscribe.llm

import java.util.Locale

/**
 * The cheap first stage of live assist (issue #50): could this
 * transcript line be a question someone wants answered? Only lines it
 * lets through are sent to the language model, which makes the final
 * call ([PromptBuilder.liveAssist] answers `NONE` for anything else),
 * so it errs on letting a line through.
 *
 * Whether a recognizer writes `?` depends on its model, so the
 * punctuation is one sign among others. A line is a candidate when,
 * after a trailing rhetorical tag (*…, right?*, *…, n'est-ce pas ?*)
 * and leading discourse words (*so*, *alors*) are set aside, it has at
 * least [MIN_WORDS] words and:
 *  - it ends in `?` (and the `?` was not the tag's), or
 *  - it opens with an English wh-word, or with an auxiliary followed by
 *    a subject (*did we…*, *can someone…*, *is the…*), or
 *  - it opens with a French interrogative (*quand*, *pourquoi*,
 *    *qu'est-ce que*…), holds *est-ce que*, or inverts its subject in
 *    its first words (*avez-vous*, *va-t-il*).
 *
 * English and French are both checked whatever the session's language:
 * it costs nothing and people mix them.
 */
object QuestionFilter {

    /** Fewer words than this is small talk (*Really?*, *How so?*). */
    const val MIN_WORDS: Int = 4

    /** Rhetorical tags at the end of a line; the line is judged without them. */
    private val TAIL_TAG = Regex(
        "[,\\s]+(?:" +
            listOf(
                "you know", "you see", "right", "ok", "okay", "yeah", "yes", "no", "huh", "eh", "correct",
                "isn't it", "aren't they", "aren't we", "don't you think", "don't you", "innit", "or not",
                "n'est-ce pas", "tu vois", "vous voyez", "tu sais", "vous savez", "hein", "non", "d'accord",
                "voilà", "oui", "pas vrai", "c'est ça"
            ).joinToString("|") { Regex.escape(it) } +
            ")[\\s?!.…]*$"
    )

    /** Words that open a line without changing what kind of sentence it is. */
    private val LEAD_WORDS = setOf(
        "so", "and", "but", "okay", "ok", "um", "uh", "erm", "well", "oh", "also", "then", "now", "hey",
        "alors", "et", "mais", "donc", "bon", "euh", "ben", "bah", "puis", "ensuite", "dis", "dites"
    )

    private val EN_WH = setOf(
        "what", "what's", "when", "when's", "where", "where's", "who", "who's", "whom", "whose", "why", "which",
        "how", "how's"
    )

    private val EN_AUX = setOf(
        "do", "does", "did", "can", "could", "should", "would", "will", "shall", "may", "might", "must",
        "is", "are", "was", "were", "am", "have", "has", "had",
        "don't", "doesn't", "didn't", "can't", "couldn't", "shouldn't", "wouldn't", "won't",
        "isn't", "aren't", "wasn't", "weren't", "haven't", "hasn't", "hadn't"
    )

    /** What may follow an auxiliary for the line to be a question rather than an order (*Do this now*). */
    private val EN_SUBJECTS = setOf(
        "i", "you", "we", "they", "he", "she", "it", "there", "that", "this", "these", "those",
        "the", "a", "an", "anyone", "anybody", "someone", "somebody", "everyone", "everybody", "nobody",
        "our", "your", "their", "his", "her", "its", "my", "any", "all", "some", "each", "either", "neither"
    )

    /**
     * What may follow *do* / *don't*, which also open orders (*Do this
     * first*, *Don't forget the slides*): a subject that cannot be an
     * object.
     */
    private val DO_SUBJECTS = setOf(
        "i", "you", "we", "they", "these", "those", "anyone", "anybody", "someone", "somebody",
        "everyone", "everybody", "people"
    )

    private val FR_OPENERS = setOf(
        "quand", "comment", "pourquoi", "qui", "quoi", "quel", "quelle", "quels", "quelles", "où", "combien",
        "lequel", "laquelle", "lesquels", "lesquelles", "qu'est-ce", "est-ce"
    )

    /** French subject inversion: *avez-vous*, *va-t-il*, *peut-on*. */
    private val FR_INVERSION = Regex("^[\\p{L}']+-(?:t-)?(?:je|tu|il|elle|on|nous|vous|ils|elles)$")

    /** Hyphenated words that look like an inversion but are not one. */
    private val FR_NOT_INVERSION = setOf("rendez-vous", "chez-nous", "vous-même", "nous-même")

    /** How many words into a line an inversion still counts (*Et quand partez-vous*). */
    private const val INVERSION_WORDS = 3

    /** Whether [line] could be a real question, worth asking the language model about. */
    fun isCandidate(line: String): Boolean {
        var text = line.replace('’', '\'').replace('‘', '\'').trim().lowercase(Locale.ROOT)
        val untagged = TAIL_TAG.replace(text, "")
        val tagged = untagged.length != text.length
        text = untagged.trim()
        val words = words(text).dropWhile { it in LEAD_WORDS }
        if (words.size < MIN_WORDS) return false
        if (!tagged && text.endsWith("?")) return true
        return opensEnglishQuestion(words, line) || isFrenchQuestion(words, text)
    }

    /** The words of [text], without the punctuation around them. */
    private fun words(text: String): List<String> = text.split(Regex("\\s+"))
        .map { it.trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '-' }.trim('-', '\'') }
        .filter { word -> word.any { it.isLetterOrDigit() } }

    private fun opensEnglishQuestion(words: List<String>, original: String): Boolean {
        val first = words[0]
        if (first in EN_WH) return true
        if (first !in EN_AUX) return false
        val next = words[1]
        val subjects = if (first == "do" || first == "don't") DO_SUBJECTS else EN_SUBJECTS
        // A capitalised word after the auxiliary is a name: "Did Marie send it".
        return next in subjects || capitalised(original, next)
    }

    private fun capitalised(original: String, word: String): Boolean = original.split(Regex("\\s+"))
        .drop(1)
        .any { it.trim { c -> !c.isLetter() }.let { w -> w.lowercase(Locale.ROOT) == word && w.first().isUpperCase() } }

    private fun isFrenchQuestion(words: List<String>, text: String): Boolean {
        val first = words[0]
        if (first in FR_OPENERS || first.startsWith("qu'est-ce") || first.startsWith("est-ce")) return true
        if (text.contains("est-ce qu")) return true
        return words.take(INVERSION_WORDS).any { it !in FR_NOT_INVERSION && FR_INVERSION.matches(it) }
    }
}
