package com.limelight.utils

import kotlin.math.roundToInt

/**
 * The client-owned cursor position (spec 1.3 D3). Pure Kotlin on purpose, so it runs in plain
 * JUnit without a phone.
 *
 * The unit is stream-area pixels: the untransformed size of the stream view. Fractions are
 * kept, so small finger moves are not lost to rounding. The position is never read back from
 * the host. Whatever the host cursor does on its own, the next touch overwrites it.
 *
 * The area is capped at Short.MAX_VALUE because the absolute mouse packet carries 16 bit values.
 */
class VirtualCursorModel(areaWidth: Int, areaHeight: Int) {

    var areaWidth: Int = clampArea(areaWidth)
        private set
    var areaHeight: Int = clampArea(areaHeight)
        private set

    var x: Float = this.areaWidth / 2f
        private set
    var y: Float = this.areaHeight / 2f
        private set

    /** Adds a delta in stream-area pixels and clamps to 0 until the last pixel. */
    fun moveBy(dx: Float, dy: Float) {
        if (dx.isFinite()) x = (x + dx).coerceIn(0f, (areaWidth - 1).toFloat())
        if (dy.isFinite()) y = (y + dy).coerceIn(0f, (areaHeight - 1).toFloat())
    }

    /** Keeps the position as the same fraction of the area, so a resize does not teleport it. */
    fun setArea(width: Int, height: Int) {
        val newWidth = clampArea(width)
        val newHeight = clampArea(height)
        if (newWidth == areaWidth && newHeight == areaHeight) return

        x = if (areaWidth > 1) x * (newWidth - 1) / (areaWidth - 1) else newWidth / 2f
        y = if (areaHeight > 1) y * (newHeight - 1) / (areaHeight - 1) else newHeight / 2f
        areaWidth = newWidth
        areaHeight = newHeight
        x = x.coerceIn(0f, (areaWidth - 1).toFloat())
        y = y.coerceIn(0f, (areaHeight - 1).toFloat())
    }

    fun reset() {
        x = areaWidth / 2f
        y = areaHeight / 2f
    }

    /** Position for the absolute packet, rounded and inside the area. */
    fun absoluteX(): Int = x.roundToInt().coerceIn(0, areaWidth - 1)

    fun absoluteY(): Int = y.roundToInt().coerceIn(0, areaHeight - 1)

    private fun clampArea(value: Int): Int = value.coerceIn(1, Short.MAX_VALUE.toInt())
}
