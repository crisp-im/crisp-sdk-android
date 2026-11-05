package im.crisp.sdk.internal.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import im.crisp.client.CrispClient.assetsLoader
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispChatboxEvent

@SuppressLint("MissingOnRenderProcessGone")
internal class CrispWebViewClient(
    context: Context,
    private val store: CrispChatboxStore,
    private val onRenderProcessGone: (view: WebView, didCrash: Boolean) -> Unit
): WebViewClient() {
    internal var baseUrl: String? = null
    private val assetLoader: WebViewAssetLoader = assetsLoader(context)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(request.getUrl())
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val url = request.getUrl().toString()
        if (url != baseUrl) {
            store.emitEvent(CrispChatboxEvent.OpenExternalUrl(url))
            return true
        }
        return false
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        if (view != null) {
            val didCrash = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || (detail?.didCrash() ?: true)
            onRenderProcessGone(view, didCrash)
        }
        return true
    }
}
