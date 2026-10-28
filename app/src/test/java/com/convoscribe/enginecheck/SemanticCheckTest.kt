package com.convoscribe.enginecheck

import com.convoscribe.llm.embedding.FakeTextEmbedder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun timesLoadPassagesAndQuestion_andClosesTheModel() {
        val fake = FakeTextEmbedder()
        var now = 0L
        // Every clock read advances 80 ms.
        val result =
            measureSemantic(
                create = { fake },
                clock = { now.also { now += 80 } },
                memoryMb = { if (it == "VmRSS") 300 else 520 }
            )

        assertNull(result.error)
        assertEquals(80L, result.loadMs)
        assertEquals(80L / SEMANTIC_CHECK_PASSAGES.size, result.passageMs)
        assertEquals(80L, result.queryMs)
        assertEquals(8, result.passages)
        assertEquals(300L to 520L, result.memoryBeforeMb to result.peakMemoryMb)
        assertEquals(8, fake.passages)
        assertTrue(fake.closed)
    }

    @Test
    fun aFailureIsReportedNotThrown() {
        val result = measureSemantic(create = { error("no model") }, clock = { 0L }, memoryMb = { null })
        assertEquals("no model", result.error)
    }

    @Test
    fun passagesAreAboutAsLongAsTheIndexs() {
        assertTrue(SEMANTIC_CHECK_PASSAGES.all { it.length in 500..1_200 })
    }

    @Test
    fun readsKilobyteFieldsAsMegabytes() {
        val status = File(
            tmp.root,
            "status"
        ).apply { writeText("Name:\tapp\nVmHWM:\t  532480 kB\nVmRSS:\t  307200 kB\n") }
        assertEquals(520L, procStatusMb("VmHWM", status))
        assertEquals(300L, procStatusMb("VmRSS", status))
        assertNull(procStatusMb("VmSwap", status))
    }
}
