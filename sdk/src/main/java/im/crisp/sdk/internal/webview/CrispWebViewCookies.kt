package im.crisp.sdk.internal.webview

import android.webkit.CookieManager
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Writes the cookies of the WebView to disk.
 *
 * The Web client saves its session in a cookie, read first when it starts, and in localStorage,
 * which the WebView both writes to disk with a delay (cookies up to about 30s).
 * If the app is killed meanwhile, the next start restores the session saved before,
 * e.g. the one reset just before (logged out). localStorage has no such API.
 */
internal object CrispWebViewCookies {
    private const val LOG_TAG = "CrispWebView"

    // Not tied to a chatbox, so that a flush started while stopping or destroying it completes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Writes the cookies to disk in the background, only once a WebView is set up. */
    fun flush() {
        scope.launch {
            try {
                // Blocking, may perform I/O
                CookieManager.getInstance().flush()
            } catch (e: Exception) {
                log(Level.WARN, LOG_TAG, "Cannot write the cookies to disk", e)
            }
        }
    }
}
