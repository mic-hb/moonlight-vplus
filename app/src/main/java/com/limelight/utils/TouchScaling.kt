package com.limelight.utils

import kotlin.math.abs

/**
 * The three unit conversions of the cursor-follow camera mode (spec 1.3 CM-10 to CM-12), as pure
 * functions so they run in plain JUnit.
 *
 * Finger coordinates reach the touch contexts already divided by the zoom ("normalized"). So a
 * normalized distance is the glass distance divided by the zoom, and each conversion below says
 * where the zoom is put back or left out.
 */
object TouchScaling {

    /**
     * Starting values for the zoom compensation (guesses, to be tuned on the phone, later exposed
     * by spec 1.5). Cursor: 0.8 is close to "constant remote distance" with some slowness. Scroll:
     * 0.6 is slower than constant at high zoom, because the constant distance felt too fast.
     */
    const val CURSOR_ZOOM_EXPONENT = 0.8f
    const val SCROLL_ZOOM_EXPONENT = 0.6f

    /** Local cursor size growth with zoom: on-screen size is zoom^exponent. 0 = constant, 1 = grows like the picture. */
    const val CURSOR_SIZE_EXPONENT = 0.5f

    /**
     * Factor applied to the cursor drawing inside the overlay, which the view transform already
     * magnified by [zoom]. Result: on-screen size = zoom^exponent times the fit size.
     */
    fun cursorContentScale(zoom: Float, exponent: Float): Float {
        val e = if (exponent.isFinite()) exponent.coerceIn(0f, 1f) else 0f
        val z = safeZoom(zoom).toDouble()
        return Math.pow(z, (e - 1f).toDouble()).toFloat()
    }

    /**
     * Cursor travel in stream-area pixels for a normalized finger delta. The zoom was divided out
     * already by the coordinate conversion, so it is NOT divided again here (spec 1.3 D7, CM-10).
     */
    fun cursorDelta(normalizedDelta: Int, sense: Double): Float =
        (normalizedDelta * sense).toFloat()

    /**
     * Cursor travel with a zoom compensation, so a zoomed cursor does not feel heavy. At exponent 0
     * this equals the plain overload, at 1 the same glass travel moves the cursor the same remote
     * distance at every zoom. Values in between give a partial compensation ("added slowness").
     */
    fun cursorDelta(normalizedDelta: Int, sense: Double, zoom: Float, exponent: Float): Float =
        (normalizedDelta * sense * compensation(zoom, exponent)).toFloat()

    /**
     * Zoom to the power of the exponent. The exponent is limited to 0..1, a zoom below 1.0 counts
     * as 1.0, so the result is 1.0 at zoom 1.0 and never below 1.0.
     */
    fun compensation(zoom: Float, exponent: Float): Double {
        val e = if (exponent.isFinite()) exponent.coerceIn(0f, 1f) else 0f
        return Math.pow(safeZoom(zoom).toDouble(), e.toDouble())
    }

    /**
     * Wheel units for a normalized finger delta. Multiplying the zoom back in makes the same glass
     * travel send the same remote scroll distance at every zoom (spec 1.3 D8, CM-11). The rounding
     * matches the old formula, so at zoom 1.0 the result is identical to the baseline.
     *
     * @param factor the context's reference factor (reference resolution over view size, times
     *   the scroll sense)
     */
    fun scrollDelta(normalizedDelta: Int, factor: Double, zoom: Float): Int =
        scrollDelta(normalizedDelta, factor, zoom, 1f)

    /**
     * Same as above with a partial compensation. Exponent 1 is the full constant remote distance,
     * a smaller exponent slows the scroll at high zoom. At zoom 1.0 the exponent has no effect.
     */
    fun scrollDelta(normalizedDelta: Int, factor: Double, zoom: Float, exponent: Float): Int {
        val sign = if (normalizedDelta < 0) -1 else 1
        return Math.round(abs(normalizedDelta) * factor * compensation(zoom, exponent) * sign).toInt()
    }

    /**
     * Glass pixels for a normalized distance, so tap, move and drag thresholds mean the same
     * finger travel at every zoom (spec 1.3 D10, CM-12). A zoom below 1.0 counts as 1.0.
     */
    fun toScreenUnits(normalizedDistance: Float, zoom: Float): Float =
        normalizedDistance * safeZoom(zoom)

    /** A zoom below 1.0, NaN or infinity counts as 1.0. */
    private fun safeZoom(zoom: Float): Float = if (zoom.isFinite() && zoom >= 1f) zoom else 1f
}
