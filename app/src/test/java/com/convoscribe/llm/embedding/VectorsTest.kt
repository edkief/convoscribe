package com.convoscribe.llm.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorsTest {

    @Test
    fun truncatesThenNormalises() {
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), Vectors.truncateAndNormalise(floatArrayOf(3f, 4f, 12f), 2), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), Vectors.truncateAndNormalise(floatArrayOf(0f, 0f), 4), 0f)
    }

    @Test
    fun cosineOfUnitVectors() {
        assertEquals(1.0, Vectors.cosine(floatArrayOf(0.6f, 0.8f), floatArrayOf(0.6f, 0.8f)), 1e-6)
        assertEquals(0.0, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(0.0, Vectors.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0.0)
    }

    @Test
    fun fakeEmbedderBringsParaphrasesTogether() {
        val fake = FakeTextEmbedder()
        val question = fake.embedQuery("How much can we spend?")
        val (budget, knee) = fake.embedPassages(listOf("The budget is ten thousand euros", "My knee hurts"))
        assertTrue(Vectors.cosine(question, budget) > 0.9)
        assertEquals(0.0, Vectors.cosine(question, knee), 1e-9)
    }
}
