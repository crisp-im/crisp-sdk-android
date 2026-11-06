package im.crisp.sdk.internal.views

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A sheet hosting the chatbox, which can be swiped down to dismiss it, as the sheet of the toolkit hosting it. */
internal interface CrispSheetDragHost {
    /**
     * Called when a drag starts, maybe while the sheet still moves once released: stops it there.
     *
     * @return where the sheet is, in px down from its place.
     */
    fun onDragStart(): Float

    /** Called on each move of the drag: moves the sheet, but not its scrim, [offsetY] px down from its place. */
    fun onDrag(offsetY: Float)

    /**
     * Called when the sheet is released [offsetY] px down from its place, with velocities in px/s (downward for
     * [velocityY]): dismisses it when released far or fast enough, otherwise moves it back to its place then calls
     * [onSettled].
     */
    fun onRelease(offsetY: Float, velocityX: Float, velocityY: Float, onSettled: () -> Unit)
}

internal object CrispSheetDrag {
    // As the Compose Material 3 ModalBottomSheet, see BottomSheetDefaults.PositionalThreshold and VelocityThreshold
    private const val COMPOSE_DISMISS_DISTANCE_DP = 56
    private const val COMPOSE_DISMISS_VELOCITY_DP = 125

    // As the ViewDragHelper of the Material Components BottomSheetBehavior
    private const val MATERIAL_BASE_SETTLE_DURATION_MS = 256
    private const val MATERIAL_MAX_SETTLE_DURATION_MS = 600

    /**
     * Whether the sheet is dismissed as a Compose Material 3 `ModalBottomSheet`: released far enough, or flung down.
     *
     * With a [velocityY] of 0, tells whether it would be dismissed if released now, as its `targetValue`.
     */
    fun shouldDismissCompose(offsetY: Float, velocityY: Float, density: Float): Boolean =
        offsetY > 0f &&
                (offsetY >= COMPOSE_DISMISS_DISTANCE_DP * density || velocityY >= COMPOSE_DISMISS_VELOCITY_DP * density)

    /**
     * Whether the sheet is dismissed as a Material Components `BottomSheetBehavior` with `skipCollapsed`:
     * released below the middle of the way down, or flung down rather than sideways faster than [velocityThreshold].
     *
     * @param sheetTop the top of the released sheet in its parent, in px.
     * @param velocityX the velocity in px/s, as clamped by [clampMaterialVelocity], as is [velocityY].
     */
    fun shouldDismissMaterial(
        sheetTop: Float,
        velocityX: Float,
        velocityY: Float,
        parentHeight: Int,
        expandedOffset: Int,
        velocityThreshold: Int
    ): Boolean =
        velocityY >= 0f &&
                ((abs(velocityX) < abs(velocityY) && velocityY > velocityThreshold) ||
                        sheetTop > (parentHeight + expandedOffset) / 2)

    /**
     * A release velocity as seen by a Material Components `BottomSheetBehavior` (from its `ViewDragHelper`):
     * 0 below the minimum fling velocity, and at most the maximum one, see [ViewConfiguration].
     */
    fun clampMaterialVelocity(velocity: Float, minVelocity: Int, maxVelocity: Int): Int {
        val value = velocity.toInt()
        return when {
            abs(value) < minVelocity -> 0
            abs(value) > maxVelocity -> if (value > 0) maxVelocity else -maxVelocity
            else -> value
        }
    }

    /**
     * The duration of a Material Components `BottomSheetBehavior` settling its released sheet [dy] px vertically,
     * as computed by its `ViewDragHelper`.
     *
     * @param velocityX the velocity in px/s, as clamped by [clampMaterialVelocity], as is [velocityY].
     * @param parentWidth the width of the sheet parent, in px.
     * @param dragRange the vertical drag range of the sheet: the parent height, as it can be hidden.
     */
    fun materialSettleDuration(
        dy: Int,
        velocityX: Int,
        velocityY: Int,
        parentWidth: Int,
        dragRange: Int
    ): Int {
        if (dy == 0) return 0
        // Weighted as ViewDragHelper.computeSettleDuration(), without horizontal move
        val weight =
            if (velocityY != 0) abs(velocityY).toFloat() / (abs(velocityX) + abs(velocityY)) else 1f
        val halfWidth = parentWidth / 2
        val distanceRatio = min(1f, abs(dy).toFloat() / parentWidth)
        val distance =
            halfWidth + halfWidth * sin((distanceRatio - 0.5f) * 0.3f * PI.toFloat() / 2f)
        val duration = if (velocityY != 0) {
            4 * (1000 * abs(distance / abs(velocityY))).roundToInt()
        } else {
            ((abs(dy).toFloat() / dragRange + 1) * MATERIAL_BASE_SETTLE_DURATION_MS).toInt()
        }
        return (min(duration, MATERIAL_MAX_SETTLE_DURATION_MS) * weight).toInt()
    }

    /** The interpolator of a Material Components `BottomSheetBehavior` settling its sheet (`ViewDragHelper`). */
    val materialSettleInterpolator = Interpolator { input ->
        val t = input - 1f
        t * t * t * t * t + 1f
    }
}

/** Receives the vertical drag of a sheet, see [CrispSheetDragDetector]. */
internal interface CrispSheetDragListener {
    /** Called when the drag starts, returns where the sheet is (maybe still moving once released), in px down. */
    fun onDragStart(): Float

    /** Called on each move, with the distance dragged down (never negative), in px. */
    fun onDrag(offsetY: Float)

    /** Called when the finger is lifted or the gesture is canceled, with its velocities in px/s (downward for Y). */
    fun onDragEnd(offsetY: Float, velocityX: Float, velocityY: Float)
}

/**
 * Detects a swipe down started anywhere on the chatbox, to drag its sheet, as the native iOS sheet does.
 *
 * The content keeps the gesture as long as it scrolls. The drag starts when it can no longer scroll up:
 * - for a WebView, when it reports pulling past its top, see [onContentOverscrollTop]. A swipe starting
 *   in a list that is not at its top scrolls it to the top, but does not drag (the page keeps the gesture).
 * - for native content that does not scroll (see [contentScrolls]), once the finger moves down past the slop.
 *
 * Positions are in screen coordinates, as the dragged view moves under the finger.
 */
internal class CrispSheetDragDetector(context: Context) {
    var listener: CrispSheetDragListener? = null

    /** Whether the content under the finger scrolls by itself, checked when the gesture starts. */
    var contentScrolls: () -> Boolean = { true }

    val isDragging: Boolean get() = state == State.DRAGGING

    private enum class State { IDLE, TRACKING, DRAGGING, IGNORED }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()
    private var state = State.IDLE
    private var canDrag = false

    // contentScrolls() when the gesture started
    private var isContentScrolling = true
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var originY = 0f
    private var offsetY = 0f
    private var velocityTracker: VelocityTracker? = null

    /**
     * To call when the content (a WebView) pulls past its top, during a touch scroll.
     *
     * Ignored once a long press is reached, e.g. a long press on a text then a drag to select it.
     */
    fun onContentOverscrollTop() {
        if (state == State.TRACKING && SystemClock.uptimeMillis() - downTime < longPressTimeout) {
            canDrag = true
        }
    }

    /** To call with every touch event, returns whether the event belongs to a drag, and not to the content. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (listener == null) {
            reset()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            state = State.TRACKING
            isContentScrolling = contentScrolls()
            canDrag = !isContentScrolling
            activePointerId = event.getPointerId(0)
            downTime = event.downTime
            downX = event.rawX
            downY = event.rawY
        }
        if (state == State.IDLE || state == State.IGNORED) return false
        track(event)
        return if (state == State.TRACKING) onTracking(event) else onDragging(event)
    }

    // Before the drag, the gesture goes to the content until it cannot scroll up anymore
    private fun onTracking(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return false
                val dx = event.rawXAt(index) - downX
                val dy = event.rawYAt(index) - downY
                val isPastSlop = abs(dx) > touchSlop || abs(dy) > touchSlop
                when {
                    canDrag && dy > touchSlop && dy > abs(dx) -> {
                        state = State.DRAGGING
                        // From where the finger and the sheet are, so that the sheet does not jump
                        offsetY = listener?.onDragStart()?.coerceAtLeast(0f) ?: 0f
                        originY = event.rawYAt(index) - offsetY
                        return true
                    }
                    // Native content: the gesture is not a swipe down, leave it
                    !isContentScrolling && isPastSlop -> state = State.IGNORED
                }
            }

            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    private fun onDragging(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    offsetY = (event.rawYAt(index) - originY).coerceAtLeast(0f)
                    listener?.onDrag(offsetY)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var velocityX = 0f
                var velocityY = 0f
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    velocityTracker?.run {
                        computeCurrentVelocity(1000)
                        velocityX = getXVelocity(activePointerId)
                        velocityY = getYVelocity(activePointerId)
                    }
                }
                val endOffsetY = offsetY
                reset()
                listener?.onDragEnd(endOffsetY, velocityX, velocityY)
            }
        }
        return true
    }

    // Follows another finger when the active one is lifted, from where the sheet is
    private fun onPointerUp(event: MotionEvent) {
        if (event.getPointerId(event.actionIndex) != activePointerId) return
        val newIndex = if (event.actionIndex == 0) 1 else 0
        activePointerId = event.getPointerId(newIndex)
        originY = event.rawYAt(newIndex) - offsetY
        downX = event.rawXAt(newIndex)
        downY = event.rawYAt(newIndex)
    }

    // Feeds the velocity tracker with screen coordinates
    private fun track(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        val screenEvent = MotionEvent.obtain(event)
        screenEvent.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        tracker.addMovement(screenEvent)
        screenEvent.recycle()
    }

    private fun reset() {
        state = State.IDLE
        canDrag = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        velocityTracker?.recycle()
        velocityTracker = null
    }
}

// Raw coordinates of any pointer, getRawX(index) and getRawY(index) are only available from Android 10
private fun MotionEvent.rawXAt(index: Int): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getRawX(index) else getX(index) + (rawX - x)

private fun MotionEvent.rawYAt(index: Int): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getRawY(index) else getY(index) + (rawY - y)
