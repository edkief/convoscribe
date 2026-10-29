package com.convoscribe.settings

/**
 * When sessions are indexed for semantic search in the background
 * (issue #65). [key] is the persisted value of
 * [AppSettings.semanticIndexMode].
 *
 *  * [CHARGING]: while the device is charging, as before.
 *  * [ANY_TIME]: whenever the battery is not low.
 *
 * *Index now* runs whatever the mode says.
 */
enum class SemanticIndexMode(val key: String) {
    CHARGING("charging"),
    ANY_TIME("anytime");

    companion object {
        /** The mode stored as [key], or `null` for an unknown value. */
        fun fromKey(key: String): SemanticIndexMode? = entries.firstOrNull { it.key == key }

        /** The mode stored as [key], or [CHARGING] for an unknown value. */
        fun of(key: String): SemanticIndexMode = fromKey(key) ?: CHARGING
    }
}
