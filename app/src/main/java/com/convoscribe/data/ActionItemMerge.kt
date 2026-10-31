package com.convoscribe.data

import com.convoscribe.llm.ExtractedActionItem

/**
 * Folds a new summary's action items into a session's existing ones
 * (issue #31), so regenerating keeps the user's work:
 *
 *  - an item the user checked, edited or deleted ([ActionItemEntity.touched])
 *    is kept as it is;
 *  - every other item is the model's and is replaced by the new list;
 *  - a new item that is the [same] as a kept one (by its current or its
 *    original text) is left out, so nothing is listed twice and a
 *    deleted item does not come back.
 *
 * New items follow the kept ones, in the summary's order.
 */
object ActionItemMerge {

    /** Rows to delete by id and rows to insert. */
    data class Result(val removeIds: List<Long>, val add: List<ActionItemEntity>)

    /** Share of words two texts must have in common to be the same item. */
    const val SAME_WORDS: Double = 0.7

    fun merge(
        sessionId: Long,
        existing: List<ActionItemEntity>,
        extracted: List<ExtractedActionItem>,
        now: Long
    ): Result {
        val kept = existing.filter { it.touched }
        val seen = kept.flatMap { listOf(it.text, it.extractedText) }.toMutableList()
        var position = (kept.maxOfOrNull { it.position } ?: -1) + 1
        val add = ArrayList<ActionItemEntity>()
        for (item in extracted) {
            if (seen.any { same(it, item.text) }) continue
            seen += item.text
            add += ActionItemEntity(
                sessionId = sessionId,
                text = item.text,
                extractedText = item.text,
                owner = item.owner,
                dueDate = item.due,
                atMs = item.atMs,
                position = position++,
                createdAt = now
            )
        }
        return Result(existing.filterNot { it.touched }.map { it.id }, add)
    }

    /**
     * Whether [a] and [b] name the same task: the same words ignoring
     * case, accents and punctuation, or at least [SAME_WORDS] of their
     * words in common.
     */
    fun same(a: String, b: String): Boolean {
        val left = SearchText.terms(a).toSet()
        val right = SearchText.terms(b).toSet()
        if (left.isEmpty() || right.isEmpty()) return SearchText.fold(a.trim()) == SearchText.fold(b.trim())
        if (left == right) return true
        val common = left.intersect(right).size.toDouble()
        return common / left.union(right).size >= SAME_WORDS
    }
}
