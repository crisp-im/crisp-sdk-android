package im.crisp.sdk.internal.webview

import android.os.Build
import im.crisp.client.CrispClient

internal object CrispWebViewVersion {
    /** Lowest WebView major version the embedded `crisp-client` bundle needs, defined with the bundle. */
    const val MIN_WEBVIEW_MAJOR = CrispClient.minWebViewMajor

    /**
     * Lowest WebView major version reporting the system bars in `env(safe-area-inset-*)`,
     * which is 0 on older ones (Chromium bug, fixed in 140 as reported by Capacitor).
     *
     * @see [Capacitor blog article](https://capawesome.io/blog/capacitor-edge-to-edge-and-safe-areas-guide/#the-old-pain-points)
     */
    const val MIN_SAFE_AREA_WEBVIEW_MAJOR = 140

    private val VERSION_NAME_REGEX = Regex("""^\s*(\d+)""")
    private val USER_AGENT_REGEX = Regex("""Chrome/(\d+)""")

    /** Major number of a WebView package version name, e.g. `118` for `118.0.5993.80`. */
    fun parseMajor(versionName: String?): Int? {
        return VERSION_NAME_REGEX.find(versionName.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Major number of the `Chrome/NNN` token of a WebView user agent. */
    fun parseMajorFromUserAgent(userAgent: String?): Int? {
        return USER_AGENT_REGEX.find(userAgent.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

    fun isSupported(major: Int, required: Int = MIN_WEBVIEW_MAJOR): Boolean = major >= required

    /**
     * Whether WebView [major] on Android [sdkInt] reports the system bars in `env(safe-area-inset-*)`:
     * - never before Android 11,
     * - only since [MIN_SAFE_AREA_WEBVIEW_MAJOR] after.
     *
     * Assumed when the version is unknown, not to inset the chatbox twice.
     */
    fun reportsSafeArea(sdkInt: Int, major: Int?): Boolean =
        sdkInt >= Build.VERSION_CODES.R && (major == null || major >= MIN_SAFE_AREA_WEBVIEW_MAJOR)
}
