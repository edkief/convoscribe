package com.convoscribe.overlay

import kotlin.math.roundToInt

/** Screen edge the floating control rests against. */
enum class Edge { START, END }

/**
 * Where the floating control rests (issue #7): against [edge], at
 * [yFraction] of the free vertical space (0 top, 1 bottom). Stored as
 * a fraction rather than pixels so it survives a rotation or a change
 * of screen size.
 */
data class FloatingPosition(val edge: Edge, val yFraction: Float) {

    /** Window x for a control [viewWidth] wide on a [screenWidth] screen, [margin] from the edge. */
    fun x(viewWidth: Int, screenWidth: Int, margin: Int): Int = when (edge) {
        Edge.START -> margin
        Edge.END -> (screenWidth - viewWidth - margin).coerceAtLeast(0)
    }

    /** Window y for a control [viewHeight] high on a [screenHeight] screen. */
    fun y(viewHeight: Int, screenHeight: Int): Int =
        ((screenHeight - viewHeight).coerceAtLeast(0) * yFraction).roundToInt()

    companion object {
        /** Right edge, a third of the way down: clear of the status bar and most app bars. */
        val DEFAULT = FloatingPosition(Edge.END, 0.3f)

        /**
         * The resting position after a drag that left the control at
         * ([x], [y]): the nearest horizontal edge (by the control's
         * centre) and the same height, kept on screen.
         */
        fun snap(
            x: Int,
            y: Int,
            viewWidth: Int,
            viewHeight: Int,
            screenWidth: Int,
            screenHeight: Int
        ): FloatingPosition {
            val centre = x + viewWidth / 2f
            val edge = if (centre < screenWidth / 2f) Edge.START else Edge.END
            val range = screenHeight - viewHeight
            val fraction = if (range <= 0) 0f else (y.toFloat() / range).coerceIn(0f, 1f)
            return FloatingPosition(edge, fraction)
        }
    }
}
