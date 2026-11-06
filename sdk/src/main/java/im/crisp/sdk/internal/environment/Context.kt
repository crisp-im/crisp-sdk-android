package im.crisp.sdk.internal.environment

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.view.ContextThemeWrapper
import android.view.View
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.crisp.sdk.R

internal tailrec fun Context.findActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal tailrec fun View.findDialogWindow(): Window? {
    val parent = parent ?: return null
    if (parent is DialogWindowProvider) return parent.window
    return (parent as? View)?.findDialogWindow()
}

internal val Context.isDebuggable: Boolean
    get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * Creates an alert dialog builder with the SDK theme,
 * whatever the app theme: the background and shape of the bottom sheets, and the SDK colors.
 */
internal fun Context.crispAlertDialogBuilder(): MaterialAlertDialogBuilder =
    // Material dialogs require a Material theme under their overlay
    MaterialAlertDialogBuilder(
        ContextThemeWrapper(this, R.style.Theme_Crisp_SDK),
        R.style.ThemeOverlay_Crisp_SDK_MaterialAlertDialog
    )

internal fun Context.showAlertDialog(message: String) {
    crispAlertDialogBuilder()
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

internal fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}
