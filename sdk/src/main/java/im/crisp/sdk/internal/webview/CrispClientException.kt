package im.crisp.sdk.internal.webview

internal sealed class CrispClientException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class AssetsMissing(
        cause: Throwable? = null
    ) : CrispClientException("crisp-client asset is missing", cause)

    class HtmlMissing(
        cause: Throwable? = null
    ) : CrispClientException("index.html is missing", cause)

    class WebsiteMissing(
        cause: Throwable? = null
    ) : CrispClientException("Website ID is missing", cause)

    class LoadingTimeout(
        cause: Throwable? = null
    ) : CrispClientException("Crisp widget did not become ready in time", cause)

    /**
     * The WebView renderer crashed ([`didCrash == true`][didCrash]) or
     * was killed by the system to reclaim memory.
     * The WebView has been replaced, so loading again can recover.
     */
    class RenderProcessGone(
        val didCrash: Boolean,
        cause: Throwable? = null
    ) : CrispClientException(
        if (didCrash) "WebView renderer crashed" else "WebView renderer was killed by the system",
        cause
    )

    /** The device's WebView cannot run the Crisp Web client. */
    sealed class WebViewUnsupported(
        message: String,
        cause: Throwable? = null
    ) : CrispClientException(message, cause)

    class WebViewTooOld(
        val installed: String,
        val required: Int,
        cause: Throwable? = null
    ) : WebViewUnsupported("WebView $installed is too old, $required or later is required", cause)

    class WebViewUnavailable(
        cause: Throwable? = null
    ) : WebViewUnsupported("No WebView is available on this device", cause)
}
