package im.crisp.sdk.internal.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnRepeat
import androidx.core.animation.doOnStart
import im.crisp.sdk.R
import kotlin.math.ceil

/** Three dots pulsing one after the other, the same loading animation as the iOS SDK. */
internal class CrispLoadingIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val dotSize = resources.getDimension(R.dimen.crisp_sdk_chatbox_loading_dot_size)
    private val dotSpacing = resources.getDimension(R.dimen.crisp_sdk_chatbox_loading_dot_spacing)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.crisp_sdk_on_surface_variant)
    }

    // Pulse of each dot, from 0 (small and faded) to 1 (full)
    private val pulses = FloatArray(DOT_COUNT)
    private val dotInterpolator = AccelerateDecelerateInterpolator()

    // Whether the cycle repeated, as dots only wait for their delay in the first one
    private var repeated = false

    // One animator for all the dots, so they stay in sync and the view is invalidated once per frame
    private val animator = ValueAnimator.ofFloat(0f, CYCLE_DURATION_MS.toFloat()).apply {
        duration = CYCLE_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val time = it.animatedValue as Float
            for (index in 0 until DOT_COUNT) {
                pulses[index] = pulse(time - DOT_DELAY_MS * index)
            }
            invalidate()
        }
        doOnStart { repeated = false }
        doOnRepeat { repeated = true }
        // Only ends by itself when animations are off: the dots are then shown in full.
        // When canceled, stopAnimating() resets them right after.
        doOnEnd {
            pulses.fill(1f)
            invalidate()
        }
    }

    init {
        contentDescription = context.getString(R.string.crisp_sdk_indicator_loading)
    }

    override fun getAccessibilityClassName(): CharSequence = ProgressBar::class.java.name

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = DOT_COUNT * dotSize + (DOT_COUNT - 1) * dotSpacing
        // Rounded up, not to clip the last dot on fractional densities
        setMeasuredDimension(
            resolveSize(ceil(width + paddingLeft + paddingRight).toInt(), widthMeasureSpec),
            resolveSize(ceil(dotSize + paddingTop + paddingBottom).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
        for (index in 0 until DOT_COUNT) {
            val pulse = pulses[index]
            val scale = DOT_MIN_SCALE + (1f - DOT_MIN_SCALE) * pulse
            // Replaces the alpha of the color, which must then stay opaque (multiply it otherwise)
            paint.alpha = ((DOT_MIN_OPACITY + (1f - DOT_MIN_OPACITY) * pulse) * 255).toInt()
            val centerX = paddingLeft + dotSize / 2 + index * (dotSize + dotSpacing)
            canvas.drawCircle(centerX, centerY, dotSize / 2 * scale, paint)
        }
    }

    // Only animates while shown, as it never ends
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startAnimating() else stopAnimating()
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    // Pulse of a dot [time] ms into its cycle, growing then shrinking back.
    private fun pulse(time: Float): Float {
        // A negative time is before the dot's delay:
        // - in the first cycle, the dot has not started yet (0),
        // - afterward, it is still finishing its previous cycle.
        val cycleTime =
            if (time < 0 && repeated) time + CYCLE_DURATION_MS else time.coerceAtLeast(0f)
        val fraction = cycleTime / DOT_ANIMATION_DURATION_MS
        return dotInterpolator.getInterpolation(if (fraction <= 1f) fraction else 2f - fraction)
    }

    private fun startAnimating() {
        if (!animator.isStarted) animator.start()
    }

    private fun stopAnimating() {
        animator.cancel()
        pulses.fill(0f)
        invalidate()
    }

    private companion object {
        const val DOT_COUNT = 3
        const val DOT_ANIMATION_DURATION_MS = 600L

        // Growing then shrinking back
        const val CYCLE_DURATION_MS = 2 * DOT_ANIMATION_DURATION_MS
        const val DOT_DELAY_MS = 200L
        const val DOT_MIN_SCALE = 0.6f
        const val DOT_MIN_OPACITY = 0.2f
    }
}
