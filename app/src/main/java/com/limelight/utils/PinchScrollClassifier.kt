package com.limelight.utils

import kotlin.math.abs
import kotlin.math.sqrt

/** Result of the two-finger classifier. UNDECIDED means "keep holding back events". */
enum class GestureDecision { UNDECIDED, PINCH, SCROLL }

/**
 * Every tunable number of [PinchScrollClassifier] in one place, so the owner can tune on the
 * phone without touching logic. All defaults are Guess (spec 1.2 section 4.3), not measured.
 *
 * Pixel fields are raw screen pixels. They are never normalized by the stream zoom, so the
 * zoom level cannot change a decision (spec 1.2 D7).
 *
 * @property pinchMinPx least pinchAmount before PINCH is allowed
 * @property scrollMinPx least scrollAmount before SCROLL is allowed
 * @property dominanceRatio how many times larger one amount must be than the other, at least 1
 * @property maxUndecidedMs hold-back cap, afterwards the larger amount wins
 * @property allowSwitch reserved for switching after a decision (spec 1.2 D8), not built, keep false
 */
data class ClassifierConfig(
    val pinchMinPx: Float,
    val scrollMinPx: Float,
    val dominanceRatio: Float = DEFAULT_DOMINANCE_RATIO,
    val maxUndecidedMs: Long = DEFAULT_MAX_UNDECIDED_MS,
    val allowSwitch: Boolean = false,
) {
    init {
        require(pinchMinPx.isFinite() && pinchMinPx > 0f) { "pinchMinPx must be finite and > 0: $pinchMinPx" }
        require(scrollMinPx.isFinite() && scrollMinPx > 0f) { "scrollMinPx must be finite and > 0: $scrollMinPx" }
        require(dominanceRatio.isFinite() && dominanceRatio >= 1f) {
            "dominanceRatio must be finite and >= 1: $dominanceRatio"
        }
        require(maxUndecidedMs > 0L) { "maxUndecidedMs must be > 0: $maxUndecidedMs" }
    }

    companion object {
        /** Guess. */
        const val DEFAULT_DOMINANCE_RATIO = 1.5f

        /** Guess. */
        const val DEFAULT_MAX_UNDECIDED_MS = 120L

        /**
         * Derives the pixel minimums from the device touch slop so they scale with screen density.
         * The caller passes ViewConfiguration.scaledTouchSlop. The factor 2 is a Guess that
         * matches the span slop of ScaleGestureDetector.
         */
        fun fromTouchSlop(touchSlopPx: Float): ClassifierConfig {
            require(touchSlopPx.isFinite() && touchSlopPx > 0f) {
                "touchSlopPx must be finite and > 0: $touchSlopPx"
            }
            return ClassifierConfig(
                pinchMinPx = 2f * touchSlopPx,
                scrollMinPx = 2f * touchSlopPx,
            )
        }
    }
}

/**
 * Decides whether a two-finger gesture is a pinch or a scroll, before any wheel event is sent.
 * Pure Kotlin on purpose, so it runs in plain JUnit and needs no phone to be tested.
 *
 * Both amounts are expressed as "pixels each finger moved". A pure scroll of d pixels gives
 * centroid travel d. A pure pinch of d pixels per finger gives span change 2d, so halving the
 * span change puts the two on the same scale.
 *
 * The decision is locked until [reset], because switching mid gesture is not built in v1.
 * No allocation per call: only primitive arguments and fields.
 */
class PinchScrollClassifier(private val config: ClassifierConfig) {

    var decision: GestureDecision = GestureDecision.UNDECIDED
        private set

    private var active = false
    private var startTimeMs = 0L
    private var startSpan = 0f
    private var startCx = 0f
    private var startCy = 0f

    /** Call at the second finger down. Positions are raw screen pixels. */
    fun begin(timeMs: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
        decision = GestureDecision.UNDECIDED
        active = true
        startTimeMs = timeMs
        startSpan = span(x0, y0, x1, y1)
        startCx = (x0 + x1) * 0.5f
        startCy = (y0 + y1) * 0.5f
    }

    /** Feed one sample. Returns the current decision. Before [begin] it stays UNDECIDED. */
    fun update(timeMs: Long, x0: Float, y0: Float, x1: Float, y1: Float): GestureDecision {
        if (!active || decision != GestureDecision.UNDECIDED) return decision

        val pinchAmount = abs(span(x0, y0, x1, y1) - startSpan) * 0.5f
        val dx = (x0 + x1) * 0.5f - startCx
        val dy = (y0 + y1) * 0.5f - startCy
        val scrollAmount = sqrt(dx * dx + dy * dy)

        val pinchReady = pinchAmount >= config.pinchMinPx
        val scrollReady = scrollAmount >= config.scrollMinPx
        val ratio = config.dominanceRatio

        if (pinchReady && pinchAmount >= ratio * scrollAmount) {
            decision = GestureDecision.PINCH
        } else if (scrollReady && scrollAmount >= ratio * pinchAmount) {
            decision = GestureDecision.SCROLL
        } else if ((pinchReady || scrollReady) && timeMs > startTimeMs &&
            timeMs - startTimeMs >= config.maxUndecidedMs
        ) {
            // Cap passed. The explicit ordering check keeps a backwards timestamp from counting
            // (and from overflowing the subtraction).
            // A tie goes to SCROLL (Guess: a wrong scroll is probably cheaper than a wrong zoom).
            decision = if (pinchAmount > scrollAmount) GestureDecision.PINCH else GestureDecision.SCROLL
        }
        return decision
    }

    /** Forget the gesture. Call when a finger lifts, a third finger lands, or on cancel. */
    fun reset() {
        decision = GestureDecision.UNDECIDED
        active = false
    }

    private fun span(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }
}
