package com.limelight.utils

import kotlin.math.pow

/**
 * The TeamViewer-like camera (spec 1.3 section 4.2). Pure Kotlin, plain numbers in and out, so it
 * runs in plain JUnit without a phone.
 *
 * Zoom is a ratio to the fit scale: 1.0 is fit. The camera never moves the cursor. For a given
 * cursor position and scale it returns where the stream view must sit so that the cursor is at the
 * viewport center, as far as the edge clamp allows. On an axis where the scaled picture is not
 * larger than the viewport, the picture is centered on that axis instead.
 *
 * The view is scaled around its top left corner, so the offset is the position of that corner in
 * viewport pixels. No allocation per call: the result is in [offsetX] and [offsetY].
 *
 * @property maxScale highest zoom ratio, at least 1.0 (spec 1.3 CM-09, default is a Guess)
 * @property pinchGain exponent on the detector's scale factor, 1.0 is one to one (CM-18, Guess)
 */
class CursorFollowCamera(
    val maxScale: Float = DEFAULT_MAX_SCALE,
    val pinchGain: Float = DEFAULT_PINCH_GAIN,
) {
    init {
        require(maxScale.isFinite() && maxScale >= 1f) { "maxScale must be finite and >= 1: $maxScale" }
        require(pinchGain.isFinite() && pinchGain > 0f) { "pinchGain must be finite and > 0: $pinchGain" }
    }

    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    /**
     * New zoom ratio after a pinch step. Hard clamp to 1.0 and [maxScale], no bounce. A bad
     * factor (NaN, zero, negative) changes nothing.
     */
    fun nextScale(currentScale: Float, detectorFactor: Float): Float {
        val current = currentScale.coerceIn(1f, maxScale)
        if (!detectorFactor.isFinite() || detectorFactor <= 0f) return current
        val next = current * detectorFactor.pow(pinchGain)
        return if (next.isFinite()) next.coerceIn(1f, maxScale) else current
    }

    /**
     * Computes the offset for the cursor at ([cursorX], [cursorY]) in stream-area pixels. At scale
     * 1.0 or below the result is exactly the base placement ([baseX], [baseY]), which the layout
     * owns (spec 1.3 CM-08).
     *
     * [keyboardHeight] is the height of the on-screen keyboard over the bottom of the viewport
     * (spec 1.4 D12, D14). Above 0 the vertical axis works on the visible part only, so the picture
     * shifts, also at scale 1.0. A bad value (NaN, negative) counts as 0, a value beyond the
     * viewport leaves one pixel visible.
     */
    fun place(
        cursorX: Float,
        cursorY: Float,
        scale: Float,
        areaWidth: Float,
        areaHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        baseX: Float,
        baseY: Float,
        keyboardHeight: Float = 0f,
    ) {
        val keyboard = if (keyboardHeight.isFinite() && keyboardHeight > 0f) keyboardHeight else 0f
        val visibleHeight = if (keyboard > 0f) maxOf(1f, viewportHeight - keyboard) else viewportHeight
        if (!(scale > 1f)) {
            offsetX = baseX
            offsetY = if (keyboard > 0f) axis(cursorY, 1f, areaHeight, visibleHeight) else baseY
            return
        }
        offsetX = axis(cursorX, scale, areaWidth, viewportWidth)
        offsetY = axis(cursorY, scale, areaHeight, visibleHeight)
    }

    private fun axis(cursor: Float, scale: Float, area: Float, viewport: Float): Float {
        val scaled = area * scale
        if (scaled <= viewport) return (viewport - scaled) / 2f
        val wanted = viewport / 2f - cursor * scale
        return wanted.coerceIn(viewport - scaled, 0f)
    }

    companion object {
        /** Guess. The V+ cap today, the real TeamViewer cap is unknown (E2 not recorded). */
        const val DEFAULT_MAX_SCALE = 10f

        /** Guess. */
        const val DEFAULT_PINCH_GAIN = 1f
    }
}
