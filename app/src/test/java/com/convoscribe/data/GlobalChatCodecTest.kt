package com.convoscribe.data

import com.convoscribe.llm.Citation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [GlobalChatCodec] (issue #49). */
class GlobalChatCodecTest {

    @Test
    fun filtersRoundTrip() {
        val filters = SearchFilters(language = "fr", since = 42L, speaker = "Élodie", tagIds = setOf(3L, 5L))
        assertEquals(filters, GlobalChatCodec.decodeFilters(GlobalChatCodec.encodeFilters(filters)))
        assertNull(GlobalChatCodec.decodeFilters(null))
    }

    @Test
    fun citationsRoundTrip_noneIsNull() {
        val citations = listOf(
            Citation(1L, "Weekly", "2026-10-01", 11L, 60_000L),
            Citation(2L, "Réunion", "2026-09-12")
        )
        assertEquals(citations, GlobalChatCodec.decodeCitations(GlobalChatCodec.encodeCitations(citations)))
        assertNull(GlobalChatCodec.encodeCitations(emptyList()))
        assertEquals(emptyList<Citation>(), GlobalChatCodec.decodeCitations(null))
    }

    @Test
    fun unreadableValuesReadAsAbsent() {
        assertNull(GlobalChatCodec.decodeFilters("not json"))
        assertEquals(emptyList<Citation>(), GlobalChatCodec.decodeCitations("{\"oops\":1}"))
    }
}
