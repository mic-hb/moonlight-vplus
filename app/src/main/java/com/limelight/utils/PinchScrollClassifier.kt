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
 * @property allowSwitch whether a decided gesture may switch to the other mode (spec 1.2 D8,
 *   batch 3c). TeamViewer switches many times in one gesture (E4, seen by the owner)
 * @property switchMinPx least amount of the other motion, inside the window, before a switch
 * @property switchRatio how many times larger the other motion must be than the current one
 * @property switchWindowMs only motion inside this recent window counts for a switch
 */
data class ClassifierConfig(
    val pinchMinPx: Float,
    val scrollMinPx: Float,
    val dominanceRatio: Float = DEFAULT_DOMINANCE_RATIO,
    val maxUndecidedMs: Long = DEFAULT_MAX_UNDECIDED_MS,
    val allowSwitch: Boolean = true,
    val switchMinPx: Float = pinchMinPx,
    val switchRatio: Float = DEFAULT_SWITCH_RATIO,
    val switchWindowMs: Long = DEFAULT_SWITCH_WINDOW_MS,
) {
    init {
        require(pinchMinPx.isFinite() && pinchMinPx > 0f) { "pinchMinPx must be finite and > 0: $pinchMinPx" }
        require(scrollMinPx.isFinite() && scrollMinPx > 0f) { "scrollMinPx must be finite and > 0: $scrollMinPx" }
        require(dominanceRatio.isFinite() && dominanceRatio >= 1f) {
            "dominanceRatio must be finite and >= 1: $dominanceRatio"
        }
        require(maxUndecidedMs > 0L) { "maxUndecidedMs must be > 0: $maxUndecidedMs" }
        require(switchMinPx.isFinite() && switchMinPx > 0f) { "switchMinPx must be finite and > 0: $switchMinPx" }
        require(switchRatio.isFinite() && switchRatio >= 1f) { "switchRatio must be finite and >= 1: $switchRatio" }
        require(switchWindowMs > 0L) { "switchWindowMs must be > 0: $switchWindowMs" }
    }

    companion object {
        /** Guess. */
        const val DEFAULT_DOMINANCE_RATIO = 1.5f

        /** Guess. */
        const val DEFAULT_MAX_UNDECIDED_MS = 120L

        /**
         * Guess. Lowered from 2 to 1 on 2026-11-08 after the owner felt a dead zone when starting
         * a tiny pinch. The Android scale detector has its own span slop on top of this.
         */
        const val PINCH_MIN_SLOP_FACTOR = 1f

        /** Guess. Higher than the first decision ratio, so a wobble does not flip the mode. */
        const val DEFAULT_SWITCH_RATIO = 2.0f

        /** Guess. */
        const val DEFAULT_SWITCH_WINDOW_MS = 200L

        /**
         * Derives the pixel minimums from the device touch slop so they scale with screen density.
         * The caller passes ViewConfiguration.scaledTouchSlop. The factor 2 is a Guess that
         * matches the span slop of ScaleGestureDetector.
         */
        fun fromTouchSlop(touchSlopPx: Float, allowSwitch: Boolean = true): ClassifierConfig {
            require(touchSlopPx.isFinite() && touchSlopPx > 0f) {
                "touchSlopPx must be finite and > 0: $touchSlopPx"
            }
            return ClassifierConfig(
                pinchMinPx = PINCH_MIN_SLOP_FACTOR * touchSlopPx,
                scrollMinPx = 2f * touchSlopPx,
                allowSwitch = allowSwitch,
                switchMinPx = 2f * touchSlopPx,
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
 * After the first decision the classifier keeps watching (when [ClassifierConfig.allowSwitch]).
 * It compares only the motion inside a short recent window, so a long scroll does not make a
 * later pinch impossible. A switch needs the other motion to pass a minimum and to beat the
 * current one by a larger ratio than the first decision, which stops a wobble from flipping the
 * mode. After a switch the window starts fresh. [reset] clears everything.
 * No allocation per call: only primitive arguments and fixed arrays.
 */
class PinchScrollClassifier(private val config: ClassifierConfig) {

    var decision: GestureDecision = GestureDecision.UNDECIDED
        private set

    private var active = false
    private var startTimeMs = 0L
    private var startSpan = 0f
    private var startCx = 0f
    private var startCy = 0f

    // Ring buffer of recent samples for switching. Fixed size, so update() never allocates.
    private val histT = LongArray(HISTORY_SIZE)
    private val histSpan = FloatArray(HISTORY_SIZE)
    private val histCx = FloatArray(HISTORY_SIZE)
    private val histCy = FloatArray(HISTORY_SIZE)
    private var histStart = 0
    private var histCount = 0

    /** Call at the second finger down. Positions are raw screen pixels. */
    fun begin(timeMs: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
        decision = GestureDecision.UNDECIDED
        active = true
        clearHistory()
        startTimeMs = timeMs
        startSpan = span(x0, y0, x1, y1)
        startCx = (x0 + x1) * 0.5f
        startCy = (y0 + y1) * 0.5f
    }

    /** Feed one sample. Returns the current decision. Before [begin] it stays UNDECIDED. */
    fun update(timeMs: Long, x0: Float, y0: Float, x1: Float, y1: Float): GestureDecision {
        if (!active) return decision
        if (decision != GestureDecision.UNDECIDED) {
            if (config.allowSwitch) evaluateSwitch(timeMs, x0, y0, x1, y1)
            return decision
        }

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
        if (decision != GestureDecision.UNDECIDED) {
            clearHistory()
            pushSample(timeMs, span(x0, y0, x1, y1), (x0 + x1) * 0.5f, (y0 + y1) * 0.5f)
        }
        return decision
    }

    private fun evaluateSwitch(timeMs: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
        val s = span(x0, y0, x1, y1)
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        // A backwards timestamp is ignored, like in the first decision.
        if (histCount > 0 && timeMs < histT[index(histCount - 1)]) return

        // Forget samples that left the window, then compare with the oldest one that is left.
        while (histCount > 0 && timeMs - histT[histStart] > config.switchWindowMs) {
            histStart = (histStart + 1) % HISTORY_SIZE
            histCount--
        }
        if (histCount > 0) {
            val a = histStart
            val pinchAmount = abs(s - histSpan[a]) * 0.5f
            val dx = cx - histCx[a]
            val dy = cy - histCy[a]
            val scrollAmount = sqrt(dx * dx + dy * dy)
            val ratio = config.switchRatio
            val min = config.switchMinPx
            val next = when (decision) {
                GestureDecision.SCROLL ->
                    if (pinchAmount >= min && pinchAmount >= ratio * scrollAmount) GestureDecision.PINCH else decision
                GestureDecision.PINCH ->
                    if (scrollAmount >= min && scrollAmount >= ratio * pinchAmount) GestureDecision.SCROLL else decision
                GestureDecision.UNDECIDED -> decision
            }
            if (next != decision) {
                decision = next
                clearHistory()
            }
        }
        pushSample(timeMs, s, cx, cy)
    }

    private fun index(offset: Int) = (histStart + offset) % HISTORY_SIZE

    private fun pushSample(timeMs: Long, span: Float, cx: Float, cy: Float) {
        if (histCount == HISTORY_SIZE) {
            histStart = (histStart + 1) % HISTORY_SIZE
            histCount--
        }
        val i = index(histCount)
        histT[i] = timeMs
        histSpan[i] = span
        histCx[i] = cx
        histCy[i] = cy
        histCount++
    }

    private fun clearHistory() {
        histStart = 0
        histCount = 0
    }

    /** Forget the gesture. Call when a finger lifts, a third finger lands, or on cancel. */
    fun reset() {
        decision = GestureDecision.UNDECIDED
        active = false
        clearHistory()
    }

    private companion object {
        /** About 250 ms of samples at 120 Hz. When full, the oldest sample is dropped. */
        const val HISTORY_SIZE = 64
    }

    private fun span(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }
}
