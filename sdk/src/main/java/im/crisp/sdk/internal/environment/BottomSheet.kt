package im.crisp.sdk.internal.environment

import android.content.res.Configuration
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.crisp.sdk.R

internal fun BottomSheetDialog.applyCrispSystemBarsConfiguration() {
    val window = window ?: return
    val context = context

    WindowCompat.setDecorFitsSystemWindows(window, false)

    val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
    insetsController.isAppearanceLightStatusBars = false
    insetsController.isAppearanceLightNavigationBars = !isDarkMode

    // For Android < 8, we apply a scrim on navigationBar because buttons are always light
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        val bottomSheet = findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val scrimTag = "crisp_sdk_nav"
        if (bottomSheet.findViewWithTag<View>(scrimTag) == null) {
            val scrimView = View(context).apply {
                tag = scrimTag
                setBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        R.color.crisp_sdk_bottomsheet_navigation_bar_color
                    )
                )
                isClickable = false
                isFocusable = false
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0 // will be updated when insets arrive
            ).apply {
                gravity = Gravity.BOTTOM
            }
            bottomSheet.addView(scrimView, lp)

            ViewCompat.setOnApplyWindowInsetsListener(scrimView) { v, insets ->
                val systemInsetsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                v.updateLayoutParams { height = systemInsetsBottom }
                insets
            }
        }
    }
}
