package com.convoscribe.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Snapping and placing the floating control (issue #7); pure arithmetic, no Android. */
class FloatingPositionTest {

    private val screenWidth = 1080
    private val screenHeight = 2000
    private val viewWidth = 400
    private val viewHeight = 100

    private fun snap(x: Int, y: Int) = FloatingPosition.snap(x, y, viewWidth, viewHeight, screenWidth, screenHeight)

    @Test
    fun snap_picksTheEdgeNearestTheControlsCentre() {
        // Centre at 339: left half.
        assertEquals(Edge.START, snap(x = 139, y = 0).edge)
        // Centre at 541: right half.
        assertEquals(Edge.END, snap(x = 341, y = 0).edge)
    }

    @Test
    fun snap_keepsTheHeightAsAFractionOfTheFreeSpace() {
        assertEquals(0.5f, snap(x = 0, y = 950).yFraction, 0.0001f)
    }

    @Test
    fun snap_clampsAControlDraggedOffScreen() {
        assertEquals(0f, snap(x = -300, y = -50).yFraction, 0f)
        assertEquals(1f, snap(x = 2000, y = 5000).yFraction, 0f)
        assertEquals(Edge.START, snap(x = -300, y = 0).edge)
        assertEquals(Edge.END, snap(x = 2000, y = 0).edge)
    }

    @Test
    fun place_putsTheControlAgainstItsEdgeWithTheMargin() {
        val start = FloatingPosition(Edge.START, 0f)
        val end = FloatingPosition(Edge.END, 1f)
        assertEquals(16, start.x(viewWidth, screenWidth, margin = 16))
        assertEquals(1080 - 400 - 16, end.x(viewWidth, screenWidth, margin = 16))
        assertEquals(0, start.y(viewHeight, screenHeight))
        assertEquals(1900, end.y(viewHeight, screenHeight))
    }

    @Test
    fun snapThenPlace_isStableOnAnotherScreenSize() {
        val position = snap(x = 700, y = 475)
        // Rotated: same edge, same relative height.
        assertEquals(Edge.END, position.edge)
        assertEquals(((1080 - 100) * 0.25f).toInt(), position.y(viewHeight, screenHeight = 1080))
    }
}
