package im.crisp.sdk.internal.webview

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import im.crisp.client.CrispClient
import im.crisp.client.CrispClient.MissingAssetsError
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.WebViewBridge
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.getUserLocales
import im.crisp.sdk.internal.environment.isDebuggable
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.ui.EdgeToEdge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class CrispWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {
    companion object {
        private const val LOG_TAG = "CrispWebView"

        // region Startup

        private const val START_UP_THREAD_NAME = "CrispWebViewStartUp"

        private val isStartedUp = AtomicBoolean(false)
        private val isStartedUpWithUiThreadTasks = AtomicBoolean(false)

        // A thread per task, catching what it throws (e.g. no WebView provider), which would crash the app
        private val startUpExecutor = Executor { command ->
            thread(name = START_UP_THREAD_NAME) {
                try {
                    command.run()
                } catch (e: Exception) {
                    log(Level.WARN, LOG_TAG, "Cannot start WebView up", e)
                }
            }
        }

        /**
         * Starts the WebView provider up ahead of time, as the first WebView of the process takes long to create.
         *
         * Once per process, as WebView stays started up, or once more to run the UI thread tasks.
         */
        fun startUp(context: Context, shouldRunUiThreadTasks: Boolean) {
            val isFirst = if (shouldRunUiThreadTasks) {
                isStartedUp.set(true)
                isStartedUpWithUiThreadTasks.compareAndSet(false, true)
            } else {
                isStartedUp.compareAndSet(false, true)
            }
            if (!isFirst) return
            val config = WebViewStartUpConfig.Builder(startUpExecutor)
                .setShouldRunUiThreadStartUpTasks(shouldRunUiThreadTasks)
                .build()
            // Started synchronously on the background thread if WebView cannot start up asynchronously
            WebViewCompat.startUpWebView(
                context.applicationContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        val message = if (shouldRunUiThreadTasks) {
                            "WebView started up, blocking the UI thread for " +
                                    "${result.totalTimeInUiThreadMillis ?: "?"}ms"
                        } else {
                            "WebView started up, without UI thread tasks"
                        }
                        log(Level.DEBUG, LOG_TAG, message)
                    }

                    override fun onError(error: WebViewStartupException) {
                        log(Level.WARN, LOG_TAG, "Cannot start WebView up", error)
                    }
                }
            )
        }

        // endregion
    }

    private lateinit var crispWebViewClient: CrispWebViewClient

    /** Typed calls to the Web client, `null` when the WebView cannot receive messages from the page. */
    internal var clientBridge: CrispClientBridge? = null
        private set

    // Rejects its pending calls when the page is replaced or destroyed, see clear(), load() and destroy()
    private var webViewBridge: WebViewBridge? = null

    fun setupWebView(
        store: CrispChatboxStore,
        onRenderProcessGone: (didCrash: Boolean) -> Unit
    ) {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        settings.apply {
            setWebContentsDebuggingEnabled(context.isDebuggable)
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        // Helpdesk links target is '_self' which opens in their iframe, change to '_blank' to open a CustomTab instead.
        // Works on 99% of devices, the other 1% will open in iframe as Crisp client already does...
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                this,
                """
                document.addEventListener('click', function(event) {
                    const link = event.target.closest('a');
                    if (link && link.href && link.target == '_self') {
                        link.target = '_blank';
                    }
                }, true);
                """.trimIndent(),
                setOf("*")
            )
        }

        crispWebViewClient = CrispWebViewClient(
            context,
            store
        ) { view, didCrash -> if (view == this) onRenderProcessGone(didCrash) }
        webViewClient = crispWebViewClient
        webChromeClient = CrispWebChromeClient(store)

        // WebView must be min v118 for Crisp widget to work properly, see CrispWebViewVersion.MIN_WEBVIEW_MAJOR
        // (checked by CrispWebViewSupport before loading, which also guarantees WEB_MESSAGE_LISTENER)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val bridge = WebViewBridge(this).also { webViewBridge = it }
            WebViewCompat.addWebMessageListener(
                this,
                WebViewBridge.JS_OBJECT_NAME,
                setOf("https://${WebViewAssetLoader.DEFAULT_DOMAIN}")
            ) { _, message, _, _, _ ->
                message.data?.let(bridge::onMessage)
            }
            clientBridge = CrispClientBridge(bridge.evaluator, bridge.events).also {
                it.bindTo(store)
            }
        }
    }

    override fun destroy() {
        webViewBridge?.rejectPendingCalls()
        super.destroy()
    }

    // region loading

    @Throws(CrispClientException::class)
    fun load(config: CrispConfig) {
        webViewBridge?.rejectPendingCalls()
        checkWebViewSupport()

        val assetsUrl = try {
            CrispClient.assetsURL(context).toString()
        } catch (e: MissingAssetsError) {
            throw CrispClientException.AssetsMissing(e)
        }

        val htmlContent = loadIndexHtml(config, assetsUrl)
        val baseUrl = "$assetsUrl/index.html"
        crispWebViewClient.baseUrl = baseUrl

        loadDataWithBaseURL(
            baseUrl,
            htmlContent,
            "text/html",
            "UTF-8",
            null
        )
    }

    @Throws(CrispClientException.HtmlMissing::class)
    private fun loadIndexHtml(config: CrispConfig, assetsUrl: String): String {
        val index = try {
            context.assets.open(CrispClient.CRISP_CLIENT_PATH + "/index.html").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            throw CrispClientException.HtmlMissing(e)
        }

        return index
            .replace("{{WEBSITE_ID}}", config.websiteId)
            .replace("{{SESSION_ID}}", config.sessionId ?: "")
            .replace("{{TOKEN_ID}}", config.tokenId ?: "")
            .replace("{{USER_LOCALES}}", "${getUserLocales(context)}")
            .replace("{{PRESENTATION}}", config.presentation.name)
            .replace("{{MOBILE_SDK_ENABLED}}", "true")
            .replace("{{CRISP_CLIENT_JS}}", CrispClient.generatedJS())
            .replace("{{ASSETS_URL}}", assetsUrl)
    }

    fun clear() {
        webViewBridge?.rejectPendingCalls()
        stopLoading()
        loadUrl("about:blank")
    }

    @Throws(CrispClientException.WebViewUnsupported::class)
    private fun checkWebViewSupport() {
        when (val support = CrispWebViewSupport.check(context)) {
            is CrispWebViewSupport.Result.Supported -> Unit
            // Do not block a WebView we know nothing about, but leave a trace if the chat then misbehaves
            is CrispWebViewSupport.Result.Unknown -> log(
                Level.WARN,
                LOG_TAG,
                "Cannot read the WebView version (${support.versionName}), " +
                        "loading anyway: WebView ${CrispWebViewVersion.MIN_WEBVIEW_MAJOR} or later is required",
            )

            is CrispWebViewSupport.Result.TooOld ->
                throw CrispClientException.WebViewTooOld(support.installed, support.required)

            is CrispWebViewSupport.Result.Unavailable -> throw CrispClientException.WebViewUnavailable()
        }
    }

    // endregion

    // region overscroll

    /**
     * Called when a touch scroll pulls the page past its top, as it cannot scroll up anymore.
     *
     * Chromium reports it when the scrolled element is the page itself,
     * or an element already at its top when the gesture started. A gesture scrolling a list stays with it, even once at its top.
     */
    internal var onOverscrollTop: (() -> Unit)? = null

    /**
     * Whether this view is moved down with its sheet (dragged, or sliding out), see [getLocationInWindow].
     */
    internal var isMovingWithSheet = false

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        if (deltaY < 0 && scrollY <= 0) {
            onOverscrollTop?.invoke()
        }
        return super.overScrollBy(
            deltaX,
            deltaY,
            scrollX,
            scrollY,
            scrollRangeX,
            scrollRangeY,
            maxOverScrollX,
            maxOverScrollY,
            isTouchEvent
        )
    }

    // From Android 11, the page shrinks its viewport to the part of this view inside the window,
    // which would keep the composer at the bottom while the sheet moves down.
    // This view is reported where it was instead.
    override fun getLocationInWindow(outLocation: IntArray) {
        super.getLocationInWindow(outLocation)
        if (isMovingWithSheet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outLocation[1] = minOf(outLocation[1], rootView.height - height)
        }
    }

    // endregion

    // region fixes

    internal fun fixSafeArea(edgeToEdge: EdgeToEdge) {
        // Before Android 11 or WebView 140, the safe area does not include the system bars, add them
        if (!CrispWebViewSupport.reportsSafeArea(context) && edgeToEdge.top) {
            val topSystemInset = ViewCompat.getRootWindowInsets(this)?.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )?.top ?: 0
            val topSystemInsetDp = (topSystemInset / resources.displayMetrics.density).toInt()

            val insetTopCss = "document.documentElement.style.setProperty(" +
                    "'--crisp-override-env-safe-area-inset-top', " +
                    "'${topSystemInsetDp}px'" +
                    ");"
            evaluateJavascript(insetTopCss, null)
        }
    }

    // endregion
}
