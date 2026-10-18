package com.convoscribe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParticipantsCodecTest {

    @Test
    fun roundTripsNamesInOrder() {
        val encoded = ParticipantsCodec.encode(listOf("Bob", "Alice"))
        assertEquals("Bob\nAlice", encoded)
        assertEquals(listOf("Bob", "Alice"), ParticipantsCodec.decode(encoded))
    }

    @Test
    fun anyoneIsNull() {
        assertNull(ParticipantsCodec.encode(null))
        assertNull(ParticipantsCodec.encode(emptyList()))
        assertNull(ParticipantsCodec.encode(listOf(" ", "")))
        assertNull(ParticipantsCodec.decode(null))
        assertNull(ParticipantsCodec.decode(""))
    }

    @Test
    fun trimsAndDropsDuplicates() {
        assertEquals(listOf("Alice", "Bob"), ParticipantsCodec.normalise(listOf(" Alice", "Bob ", "Alice")))
    }
}
