package com.convoscribe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The floating control's shapes and their defaults (issue #21). */
class FloatingShapeTest {

    @Test
    fun resolve_defaultsToMinimalOnEinkAndStandardElsewhere() {
        assertEquals(FloatingShape.MINIMAL, FloatingShape.resolve(null, eink = true))
        assertEquals(FloatingShape.STANDARD, FloatingShape.resolve(null, eink = false))
    }

    @Test
    fun resolve_anExplicitChoiceWins() {
        assertEquals(FloatingShape.EXPANDED, FloatingShape.resolve("expanded", eink = true))
        assertEquals(FloatingShape.MINIMAL, FloatingShape.resolve("minimal", eink = false))
    }

    @Test
    fun resolve_anUnknownKeyFallsBackToTheDefault() {
        assertNull(FloatingShape.fromKey("huge"))
        assertEquals(FloatingShape.MINIMAL, FloatingShape.resolve("huge", eink = true))
    }

    @Test
    fun next_growsThenWrapsToMinimal() {
        assertEquals(FloatingShape.STANDARD, FloatingShape.MINIMAL.next())
        assertEquals(FloatingShape.EXPANDED, FloatingShape.STANDARD.next())
        assertEquals(FloatingShape.MINIMAL, FloatingShape.EXPANDED.next())
    }
}
