package im.crisp.sdk.internal.views

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.WindowInsets
import android.widget.LinearLayout
import androidx.core.view.WindowInsetsCompat
import im.crisp.sdk.R
import kotlin.math.max

/**
 * Base of the SDK native views, themed with [R.style.Theme_Crisp_SDK] for their Material components to work
 * whatever the app theme.
 *
 * Subclasses must create their children from [getContext] (the themed one), not from their constructor parameter
 * (the host one), hence its `hostContext` name.
 */
internal abstract class CrispNativeView @JvmOverloads constructor(
    hostContext: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(ContextThemeWrapper(hostContext, R.style.Theme_Crisp_SDK), attrs, defStyleAttr) {
    abstract fun getPadding(): Int

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val insetsCompat = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        val systemInsets = insetsCompat.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
        )

        val padding = getPadding()

        setPadding(
            max(padding, systemInsets.left),
            max(padding, systemInsets.top),
            max(padding, systemInsets.right),
            max(padding, systemInsets.bottom)
        )

        return insets
    }
}