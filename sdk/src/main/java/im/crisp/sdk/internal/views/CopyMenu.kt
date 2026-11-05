package im.crisp.sdk.internal.views

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import im.crisp.sdk.R
import im.crisp.sdk.internal.environment.copyToClipboard

/**
 * Shows a contextual menu with a single "Copy" action for [text], anchored at ([x], [y]) in CSS
 * pixels relative to this [FrameLayout].
 */
internal fun FrameLayout.showCopyMenu(text: String, x: Double, y: Double) {
    val density = resources.displayMetrics.density
    val anchor = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(1, 1).also {
            it.leftMargin = (x * density).toInt()
            it.topMargin = (y * density).toInt()
        }
    }
    addView(anchor)
    // AppCompat one with the SDK theme, styled like the bottom sheets whatever the app theme
    PopupMenu(ContextThemeWrapper(context, R.style.Theme_Crisp_SDK), anchor).apply {
        menu.add(R.string.crisp_sdk_action_copy).setOnMenuItemClickListener {
            context.copyToClipboard(text)
            true
        }
        setOnDismissListener { (anchor.parent as? ViewGroup)?.removeView(anchor) }
        show()
    }
}
