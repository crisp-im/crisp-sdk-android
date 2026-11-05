package im.crisp.sdk.internal.webview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebSettings
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat

/**
 * Tells whether the device's Android System WebView can run the Crisp chatbox.
 *
 * The chatbox checks it before loading and shows an error when it is [Result.TooOld] or [Result.Unavailable].
 * Nothing here shows any UI.
 */
internal object CrispWebViewSupport {
    private const val DEFAULT_WEBVIEW_PACKAGE = "com.google.android.webview"

    sealed interface Result {
        /** The installed WebView is recent enough. */
        data object Supported : Result

        /**
         * The WebView works but its version could not be read from its package nor its user agent
         * (non-standard version string, unusual build). Not blocked, as nothing says it is too old.
         */
        data class Unknown(val versionName: String?) : Result

        /** The installed WebView is older than [required]. */
        data class TooOld(
            val installed: String,
            val installedMajor: Int,
            val required: Int,
        ) : Result

        /** No usable WebView provider on this device (missing or disabled). */
        data object Unavailable : Result
    }

    /**
     * Checks the installed WebView against the minimum version the chatbox needs.
     * Unlike a failed WebView creation, it never throws.
     */
    fun check(context: Context): Result {
        return try {
            val versionName = WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            val installedMajor = installedMajor(context, versionName) ?: return Result.Unknown(versionName)

            if (CrispWebViewVersion.isSupported(installedMajor)) {
                Result.Supported
            } else {
                Result.TooOld(
                    installed = versionName ?: installedMajor.toString(),
                    installedMajor = installedMajor,
                    required = CrispWebViewVersion.MIN_WEBVIEW_MAJOR,
                )
            }
        } catch (e: Exception) {
            Result.Unavailable
        }
    }

    // Read once per process, as updating WebView kills the apps using it
    @Volatile
    private var reportsSafeArea: Boolean? = null

    /**
     * Whether the installed WebView reports the system bars in `env(safe-area-inset-*)`,
     * or else the chatbox insets the page itself (see [CrispWebViewVersion.reportsSafeArea]).
     */
    fun reportsSafeArea(context: Context): Boolean = reportsSafeArea ?: run {
        val major = try {
            installedMajor(context, WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
        } catch (e: Exception) {
            null
        }
        CrispWebViewVersion.reportsSafeArea(Build.VERSION.SDK_INT, major).also { reportsSafeArea = it }
    }

    // No package info, ask the WebView itself. Throws when there is no provider at all.
    private fun installedMajor(context: Context, versionName: String?): Int? =
        CrispWebViewVersion.parseMajor(versionName)
            ?: CrispWebViewVersion.parseMajorFromUserAgent(WebSettings.getDefaultUserAgent(context))

    /**
     * Whether a Play Store is available to [openWebViewUpdate].
     */
    fun canOpenWebViewUpdate(context: Context): Boolean {
        return storeIntent(context, "market://details?id=${webViewPackageName(context)}")
            .resolveActivity(context.packageManager) != null
    }

    /**
     * Opens the Play Store listing of the active WebView package (Android System WebView, or Chrome
     * when it is the provider).
     *
     * @return `false` if nothing could open it.
     */
    fun openWebViewUpdate(context: Context): Boolean {
        val packageName = webViewPackageName(context)
        return listOf(
            "market://details?id=$packageName",
            "https://play.google.com/store/apps/details?id=$packageName",
        ).any { url ->
            try {
                context.startActivity(storeIntent(context, url))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }

    private fun webViewPackageName(context: Context): String {
        return runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull()
            ?: DEFAULT_WEBVIEW_PACKAGE
    }

    private fun storeIntent(context: Context, url: String): Intent {
        return Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
