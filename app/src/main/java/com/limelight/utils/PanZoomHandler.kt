package com.limelight.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

import com.limelight.Game
import com.limelight.preferences.PreferenceConfiguration

class PanZoomHandler(
    context: Context,
    private val game: Game,
    private val streamView: View,
    private val cursorOverlay: View,
    private val prefConfig: PreferenceConfiguration
) {
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var parent: View? = null
    private var scaleFactor = 1.0f
    private var childX = 0f
    private var childY = 0f
    private var parentWidth = 0f
    private var parentHeight = 0f
    private var childWidth = 0f
    private var childHeight = 0f
    private var imeOffsetY = 0f
    private var userTransformActive = false
    var onUserTransform: (() -> Unit)? = null

    // Spec 1.4 batch 6. Height of the on-screen keyboard over the bottom of the parent. The camera
    // treats the part above it as the viewport (D12). Zero when the keyboard is closed.
    private var keyboardHeight = 0f

    /** Runs after the camera placed the picture while a keyboard is open, so the text focus offset can follow. */
    var onCameraPlaced: (() -> Unit)? = null

    /** Runs when the parent size changed (rotation, split screen), so active touches can be cancelled (D13). */
    var onViewportChanged: (() -> Unit)? = null
    private var lastParentWidth = 0f
    private var lastParentHeight = 0f

    // Trackpad pinch mode (spec 1.2). Only the scale detector is fed, and onScale is gated
    // until the classifier decides PINCH. False in the old Pan and Zoom toggle mode.
    private var trackpadDetectorMode = false
    private var trackpadZoomAllowed = false

    // Cursor-follow camera (spec 1.3 batch 4). Active in the trackpad mode when a cursor model
    // exists. Then a pinch changes only the scale and the camera places the picture so the cursor
    // is at the viewport center. The old finger pivot code stays for the old Pan and Zoom mode.
    private var camera = CursorFollowCamera(maxScale = MAX_SCALE)
    private var cursorSizeExponent = TouchScaling.CURSOR_SIZE_EXPONENT
    private var cursorModel: VirtualCursorModel? = null

    init {
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())

        // Everything gets easier with 0,0 as the pivot point
        streamView.pivotX = 0f
        streamView.pivotY = 0f
        cursorOverlay.pivotX = 0f
        cursorOverlay.pivotY = 0f
    }

    fun handleTouchEvent(motionEvent: MotionEvent) {
        if (trackpadDetectorMode) {
            trackpadDetectorMode = false
            trackpadZoomAllowed = false
            scaleGestureDetector.isQuickScaleEnabled = true
        }
        scaleGestureDetector.onTouchEvent(motionEvent)
        gestureDetector.onTouchEvent(motionEvent)
    }

    /**
     * Trackpad mode feed: scale detector only, so one finger never pans the view directly and
     * double-tap-and-drag stays a drag select (quick scale off).
     */
    fun handleTrackpadTouchEvent(motionEvent: MotionEvent) {
        if (!trackpadDetectorMode) {
            trackpadDetectorMode = true
            scaleGestureDetector.isQuickScaleEnabled = false
        }
        scaleGestureDetector.onTouchEvent(motionEvent)
    }

    /**
     * Takes the tunable zoom values (spec 1.5). Called at the start of a touch gesture. Only the
     * cursor-follow camera uses them, the old Pan and Zoom mode keeps its constants.
     */
    fun applySettings(settings: TeamViewerTouchSettings) {
        if (camera.maxScale != settings.maxZoom || camera.pinchGain != settings.pinchGain) {
            camera = CursorFollowCamera(settings.maxZoom, settings.pinchGain)
            if (scaleFactor > settings.maxZoom) scaleFactor = settings.maxZoom
        }
        if (cursorSizeExponent != settings.cursorSizeExponent) {
            cursorSizeExponent = settings.cursorSizeExponent
            applyTransformIfReady()
        }
    }

    private fun applyTransformIfReady() {
        if (childWidth == 0f || childHeight == 0f || parent == null) return
        applyTransform()
    }

    /** Gives the camera the cursor to follow. Null leaves the old finger pivot behavior. */
    fun setCursorModel(model: VirtualCursorModel?) {
        cursorModel?.onChanged = null
        cursorModel = model
        model?.onChanged = { followCursor() }
    }

    private fun cameraMode(): Boolean = trackpadDetectorMode && cursorModel != null

    /** Moves the picture so the cursor stays at the viewport center. Cursor moves are not a user pan. */
    private fun followCursor() {
        val model = cursorModel ?: return
        if (!trackpadDetectorMode || (scaleFactor <= 1f && keyboardHeight <= 0f)) return
        placeWithCamera(model)
    }

    /**
     * Tells the camera how much of the parent the keyboard covers (spec 1.4 LY-11). Only the
     * cursor-follow camera reacts. Other modes keep the text focus offset alone.
     */
    fun setKeyboardHeight(height: Float) {
        val clean = if (height.isFinite() && height > 0f) height else 0f
        if (clean == keyboardHeight) return
        keyboardHeight = clean
        val model = cursorModel ?: return
        if (!cameraMode()) return
        placeWithCamera(model)
    }

    private fun placeWithCamera(model: VirtualCursorModel) {
        if (!updateDimensions()) return
        camera.place(
            model.x, model.y, scaleFactor,
            streamView.width.toFloat(), streamView.height.toFloat(),
            parentWidth, parentHeight,
            streamView.left.toFloat(), streamView.top.toFloat(),
            keyboardHeight,
        )
        childX = camera.offsetX
        childY = camera.offsetY
        applyTransform()
        if (keyboardHeight > 0f) onCameraPlaced?.invoke()
    }

    /** True once the classifier decided PINCH, false at gesture end or on any other decision. */
    fun setTrackpadZoomAllowed(allowed: Boolean) {
        trackpadZoomAllowed = allowed
    }

    private fun updateDimensions(): Boolean {
        if (parent == null) parent = streamView.parent as? View
        val currentParent = parent ?: return false
        childHeight = streamView.height * scaleFactor
        childWidth = streamView.width * scaleFactor
        parentWidth = currentParent.width.toFloat()
        parentHeight = currentParent.height.toFloat()
        return streamView.width > 0 && streamView.height > 0 &&
            currentParent.width > 0 && currentParent.height > 0
    }

    private fun constrainToBounds() {
        if (!updateDimensions()) return

        if (parentWidth >= childWidth) {
            childX = (parentWidth - childWidth) / 2
        } else {
            val boundaryX = parentWidth - childWidth
            childX = maxOf(boundaryX, minOf(childX, 0f))
        }

        if (parentHeight >= childHeight) {
            childY = (parentHeight - childHeight) / 2
        } else {
            val boundaryY = parentHeight - childHeight
            childY = maxOf(boundaryY, minOf(childY, 0f))
        }

        applyTransform()
    }

    private fun applyTransform() {
        streamView.scaleX = scaleFactor
        streamView.scaleY = scaleFactor
        streamView.x = childX
        streamView.y = childY + imeOffsetY

        cursorOverlay.scaleX = scaleFactor
        cursorOverlay.scaleY = scaleFactor
        cursorOverlay.x = childX
        cursorOverlay.y = childY + imeOffsetY
        // Cursor size grows only partly with zoom in the cursor-follow camera mode (spec 1.4 batch 6b).
        (cursorOverlay as? com.limelight.ui.CursorView)?.setContentScale(
            if (cameraMode() && scaleFactor > 1f) {
                TouchScaling.cursorContentScale(scaleFactor, cursorSizeExponent)
            } else {
                1f
            }
        )
    }

    fun setImeOffsetY(offsetY: Float) {
        imeOffsetY = offsetY.coerceAtMost(0f)
        if (childWidth == 0f || childHeight == 0f || parent == null) return
        applyTransform()
    }

    fun captureYToParent(y: Int, captureHeight: Int): Float {
        if (captureHeight <= 0) return childY
        if (!updateDimensions()) return childY
        return childY + y.coerceIn(0, captureHeight).toFloat() / captureHeight * childHeight
    }

    fun handleSurfaceChange() {
        notifyViewportChange()
        val model = cursorModel
        if (userTransformActive && model != null && cameraMode()) {
            // The ratio to fit stays. Only the viewport changed, so ask the camera again.
            parent = streamView.parent as? View
            placeWithCamera(model)
            return
        }
        if (!userTransformActive) {
            // DisplayPositionManager owns the base placement until the user pans or zooms.
            // View.left/top exclude our temporary translation and therefore preserve every
            // gravity, offset, stretch, rotation, and resolution-driven layout update.
            parent = streamView.parent as? View
            if (!updateDimensions()) return
            childX = streamView.left.toFloat()
            childY = streamView.top.toFloat()
            applyTransform()
            return
        }

        if (childWidth == 0f || parent == null) return

        val prevChildWidth = childWidth
        val prevChildHeight = childHeight
        val prevParentWidth = parentWidth
        val prevParentHeight = parentHeight

        if (!updateDimensions()) return

        val viewScaleX = childWidth / prevChildWidth
        val viewScaleY = childHeight / prevChildHeight

        val dPivotX1 = childX - prevParentWidth / 2
        val dPivotY1 = childY - prevParentHeight / 2

        val dPivotX2 = dPivotX1 * viewScaleX
        val dPivotY2 = dPivotY1 * viewScaleY

        childX = dPivotX2 + parentWidth / 2
        childY = dPivotY2 + parentHeight / 2

        constrainToBounds()
    }

    private fun notifyViewportChange() {
        val currentParent = (parent ?: streamView.parent as? View) ?: return
        val w = currentParent.width.toFloat()
        val h = currentParent.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val changed = lastParentWidth > 0f && (w != lastParentWidth || h != lastParentHeight)
        lastParentWidth = w
        lastParentHeight = h
        if (changed && cameraMode()) onViewportChanged?.invoke()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Returning true keeps the detector's span current, so opening the gate does not jump.
            if (trackpadDetectorMode && !trackpadZoomAllowed) return true
            val model = cursorModel
            if (model != null && trackpadDetectorMode) {
                scaleFactor = camera.nextScale(scaleFactor, detector.scaleFactor)
                userTransformActive = scaleFactor > 1f
                placeWithCamera(model)
                // No onUserTransform here: with the cursor-follow camera a pinch does not freeze
                // the keyboard offset (spec 1.4 D11).
                return true
            }
            val previousX = childX
            val previousY = childY
            val previousScale = scaleFactor
            var newScaleFactor = scaleFactor * detector.scaleFactor
            newScaleFactor = maxOf(1f, minOf(newScaleFactor, MAX_SCALE)) // Apply minimum scale

            // Calculate pivot point
            val focusX = detector.focusX
            val focusY = detector.focusY

            val dPivotX = (childX - focusX) / scaleFactor * newScaleFactor
            val dPivotY = (childY + imeOffsetY - focusY) / scaleFactor * newScaleFactor

            childX = focusX + dPivotX
            childY = focusY + dPivotY - imeOffsetY

            scaleFactor = newScaleFactor

            constrainToBounds()
            if (childX != previousX || childY != previousY || scaleFactor != previousScale) {
                userTransformActive = true
                onUserTransform?.invoke()
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            game.updatePipAutoEnter()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val previousX = childX
            val previousY = childY
            // View coordinates include the temporary IME offset. Gesture state
            // must remain in the persistent base transform or the IME offset is
            // folded into childY and applied twice on the next transform.
            childX -= distanceX
            childY -= distanceY

            constrainToBounds()
            if (childX != previousX || childY != previousY) {
                userTransformActive = true
                onUserTransform?.invoke()
            }
            return true
        }
    }

    companion object {
        private const val MAX_SCALE = 10.0f
    }
}
