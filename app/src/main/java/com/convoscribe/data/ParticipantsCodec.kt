package com.convoscribe.data

/**
 * Round-trip a session's expected participants (enrolled names)
 * through the nullable `sessions.participants` column: one name per
 * line. `null` means "anyone" and is distinct from an empty list,
 * which the UI never produces (choosing nobody falls back to anyone).
 *
 * Names are trimmed, blanks and duplicates dropped, and the order
 * kept, so the column round-trips exactly what the picker showed.
 */
object ParticipantsCodec {

    fun encode(names: List<String>?): String? {
        val clean = normalise(names) ?: return null
        return clean.joinToString("\n")
    }

    fun decode(value: String?): List<String>? = normalise(value?.split('\n'))

    /** Trimmed, de-duplicated names, or `null` for anyone (including an empty list). */
    fun normalise(names: List<String>?): List<String>? {
        if (names == null) return null
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return clean.ifEmpty { null }
    }
}
