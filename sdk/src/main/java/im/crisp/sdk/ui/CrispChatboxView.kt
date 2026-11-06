package im.crisp.sdk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispChatboxController
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.api.Presentation
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.chatbox.CrispChatboxEvent
import im.crisp.sdk.internal.chatbox.CrispChatboxState
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispControllerDispatcher
import im.crisp.sdk.internal.chatbox.CrispSessions
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.findActivity
import im.crisp.sdk.internal.environment.isDebuggable
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.environment.openExternalUrl
import im.crisp.sdk.internal.environment.showAlertDialog
import im.crisp.sdk.internal.permission.CrispPermissionManager
import im.crisp.sdk.internal.upload.CrispUploadManager
import im.crisp.sdk.internal.views.CrispClientErrorView
import im.crisp.sdk.internal.views.CrispLoadingView
import im.crisp.sdk.internal.views.CrispSDKErrorView
import im.crisp.sdk.internal.views.CrispSheetDragDetector
import im.crisp.sdk.internal.views.CrispSheetDragHost
import im.crisp.sdk.internal.views.CrispSheetDragListener
import im.crisp.sdk.internal.views.showCopyMenu
import im.crisp.sdk.internal.webview.CrispClientException
import im.crisp.sdk.internal.webview.CrispWebView
import im.crisp.sdk.internal.webview.CrispWebViewSupport
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The chatbox as a `View`, embedded in your layout.
 *
 * In XML, with the `app:crisp_sdk_website_id`, `app:crisp_sdk_token_id` and `app:crisp_sdk_edge_to_edge` attributes:
 * ```xml
 * <im.crisp.sdk.ui.CrispChatboxView
 *     android:id="@+id/chatbox_view"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     app:crisp_sdk_website_id="@string/crisp_website_id"
 *     app:crisp_sdk_edge_to_edge="top" />
 * ```
 *
 * In code, with [enableEdgeToEdge] and [load]:
 * ```
 * val chatboxView = CrispChatboxView(context).apply {
 *     enableEdgeToEdge(EdgeToEdge.ALL)
 *     load(CrispConfig(websiteId = "YOUR_WEBSITE_ID", tokenId = user.id))
 * }
 * container.addView(chatboxView)
 * ```
 * [load] also overrides the attributes, or switches to another website or user later on.
 *
 * The [controller] drives its chatbox. The default one lives as long as this view,
 * so register its callbacks on each creation of your `Activity`:
 * ```
 * val chatboxView = findViewById<CrispChatboxView>(R.id.chatbox_view)
 * chatboxView.controller.addEventCallbacks(CrispEventCallbacks(onChatOpened = { ... }))
 * ```
 * To keep the pending calls too when the view is recreated, e.g. on rotation, set your own from a `ViewModel`,
 * see [CrispChatboxController].
 *
 * The WebView is destroyed with the lifecycle hosting this view. If you remove this view before that,
 * call its [onDestroy] method.
 *
 * The test app has a [sample in XML](https://github.com/crisp-im/crisp-sdk-android/blob/beta/app/src/main/java/im/crisp/catalog/XmlViewActivity.kt).
 *
 * @see EdgeToEdge
 */
@SuppressLint("SetJavaScriptEnabled")
class CrispChatboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private companion object {
        private const val LOG_TAG = "CrispChatboxView"
        private val LOADING_TIMEOUT_DEBUG = 5.seconds
        private val LOADING_TIMEOUT_RELEASE = 15.seconds
        private const val FADE_DURATION_MS = 250L
    }

    private val store: CrispChatboxStore = CrispChatboxStore()
    private val permissionManager: CrispPermissionManager = CrispPermissionManager(context)
    private val uploadManager: CrispUploadManager = CrispUploadManager(context, permissionManager)

    // region Controller

    private var ownsController = true

    /**
     * Drives the Web client running in this chatbox, bound while this view is attached to a window.
     *
     * Can be replaced to keep the same controller across views, e.g. when a host recreates the view.
     *
     * The default one is closed when replaced or when this view is destroyed, a replacing one is
     * left to its owner.
     *
     * Replacing it does not notify [onChatOpened][im.crisp.sdk.api.CrispEventCallbacks.onChatOpened]
     * or [onChatClosed][im.crisp.sdk.api.CrispEventCallbacks.onChatClosed], as the chat stays shown:
     * the next ones are notified to the replacing one.
     */
    var controller: CrispChatboxController = CrispChatboxController()
        set(value) {
            if (field !== value) {
                webView?.clientBridge?.let(field::detach)
                if (ownsController) {
                    field.close()
                    ownsController = false
                }
                field = value
                if (isAttachedToWindow) {
                    webView?.clientBridge?.let(::attachController)
                }
            }
        }

    private fun attachController(bridge: CrispClientBridge) {
        controller.attach(
            bridge,
            store.config,
            store.state,
            store.clientEvents,
            controllerDispatcher
        )
    }

    // endregion

    // region Views

    private val loadingView: CrispLoadingView = CrispLoadingView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }
    private val sdkErrorView: CrispSDKErrorView = CrispSDKErrorView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        isVisible = false
    }
    private val clientErrorView: CrispClientErrorView = CrispClientErrorView(context).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        isVisible = false
    }

    // Null until created (see scheduleWebViewCreation),
    // when the device has no WebView provider (see CrispWebViewSupport) or once destroyed
    private var webView: CrispWebView? = null

    init {
        addView(loadingView)
        loadingView.onCancelClickListener = {
            store.emitEvent(CrispChatboxEvent.Close)
        }

        addView(clientErrorView)
        clientErrorView.onCancelClickListener = {
            store.emitEvent(CrispChatboxEvent.Close)
        }
        clientErrorView.onRetryClickListener = {
            load()
        }

        addView(sdkErrorView)
    }

    /**
     * Fades this native view in or out over the WebView, laid out below them,
     * so that switching between native views or to the Web client cross-fades.
     */
    private fun View.fadeVisible(visible: Boolean) {
        if (visible == isVisible && (!visible || alpha == 1f)) return
        animate().cancel()
        if (visible) {
            if (!isVisible) {
                alpha = 0f
                isVisible = true
            }
            animate().alpha(1f).setDuration(FADE_DURATION_MS)
        } else {
            // Not run if canceled, e.g. when faded in again meanwhile
            animate().alpha(0f).setDuration(FADE_DURATION_MS).withEndAction { isVisible = false }
        }
    }

    // endregion

    // region Lifecycle

    // The lifecycle hosting this view (Activity, Fragment view, etc.), destroying the WebView with it
    private var observedLifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START, Lifecycle.Event.ON_STOP -> updateChatOpened()
            Lifecycle.Event.ON_DESTROY -> onDestroy()
            else -> Unit
        }
    }

    private fun observeLifecycle() {
        val lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        if (lifecycle !== observedLifecycle) {
            observedLifecycle?.removeObserver(lifecycleObserver)
            observedLifecycle = lifecycle?.also { it.addObserver(lifecycleObserver) }
        }
    }

    private fun isStarted(): Boolean =
        observedLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context.findActivity() as? androidx.activity.ComponentActivity)?.let { activity ->
            uploadManager.registerLaunchers(activity)
            permissionManager.registerLaunchers(activity)
        }
        observeLifecycle()
        updateChatOpened()
        webView?.clientBridge?.let(::attachController)
        // Loads once started, if needed
        startStateEventCollection()
    }

    override fun onDetachedFromWindow() {
        // Still attached until super is called
        updateChatOpened(isAttached = false)
        webView?.clientBridge?.let(controller::detach)
        // The loading timeout is canceled below, so reload when attached again
        if (store.state.value is CrispChatboxState.Loading) {
            needsLoad = true
        }
        uploadManager.unregisterLaunchers()
        uploadManager.dismiss()
        permissionManager.unregisterLaunchers()
        permissionManager.dismiss()
        cancelStateEventCollection()
        cancelLoadingTimeout()
        // Scheduled again once attached, as still needing to load
        cancelWebViewCreation()
        super.onDetachedFromWindow()
    }

    /**
     * Destroys the WebView and closes the default [controller]. This view cannot be used afterwards.
     *
     * Called automatically when the lifecycle hosting this view is [DESTROYED][Lifecycle.State.DESTROYED]
     * (when [Activity.onDestroy][android.app.Activity.onDestroy] or
     * [Fragment.onDestroy][androidx.fragment.app.Fragment.onDestroy] is called), which covers most cases.
     *
     * Call it yourself when you remove this view for good before that,
     * e.g. `container.removeView(chatboxView)` in an `Activity` that keeps running.
     * Until then, the lifecycle keeps this view and its running WebView in memory.
     *
     * Calling it more than once has no effect.
     */
    fun onDestroy() {
        cancelWebViewCreation()
        // Never created once destroyed
        isWebViewCreated = true
        cancelSessionPreparation()
        observedLifecycle?.removeObserver(lifecycleObserver)
        observedLifecycle = null
        // Closed if destroyed while still attached and started, no longer opened without a lifecycle
        updateChatOpened()
        webView?.let {
            it.clear()
            removeWebView(it)
        }
        if (ownsController) {
            controller.close()
        }
    }

    // endregion

    // region onChatOpened

    // Whether the chat is shown to the user (drawn while attached to a window and started), see updateChatOpened
    private var isChatOpened = false
    private val chatOpenedListener = ViewTreeObserver.OnPreDrawListener {
        cancelChatOpened()
        if (!isChatOpened && isAttachedToWindow && isStarted()) {
            isChatOpened = true
            controller.notifyChatOpened()
        }
        true
    }

    // The observer waiting for the next draw to notify that the chat is opened, null if not waiting
    private var chatOpenedObserver: ViewTreeObserver? = null

    /**
     * Notifies the [controller] when the chat is opened (drawn while attached to a window and started),
     * or closed, whatever its loading state.
     *
     * Opened is notified on the next draw rather than right away, as callbacks may be registered
     * once attached in the same frame, e.g. Compose effects run after the view is attached.
     * Closed is notified right away, or not at all if opened was not notified yet.
     */
    private fun updateChatOpened(isAttached: Boolean = isAttachedToWindow) {
        if (isAttached && isStarted()) {
            if (!isChatOpened && chatOpenedObserver == null) {
                chatOpenedObserver =
                    viewTreeObserver.also { it.addOnPreDrawListener(chatOpenedListener) }
            }
        } else {
            cancelChatOpened()
            if (isChatOpened) {
                isChatOpened = false
                controller.notifyChatClosed()
            }
        }
    }

    private fun cancelChatOpened() {
        // Removing while dispatching is supported, but an observer that is no longer alive throws
        chatOpenedObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(chatOpenedListener)
        chatOpenedObserver = null
    }

    // endregion

    // region EdgeToEdge and Insets

    private var edgeToEdge: EdgeToEdge = EdgeToEdge.NONE
        set(value) {
            if (field != value) {
                field = value
                ViewCompat.requestApplyInsets(this)
            }
        }

    /** Draws both edges of this chatbox behind the system bars, see [EdgeToEdge.ALL]. */
    fun enableEdgeToEdge() {
        edgeToEdge = EdgeToEdge.ALL
    }

    /** Draws the given edges of this chatbox behind the system bars, see [EdgeToEdge]. */
    fun enableEdgeToEdge(edgeToEdge: EdgeToEdge) {
        this.edgeToEdge = edgeToEdge
    }

    /** Draws the given edges of this chatbox behind the system bars, see [EdgeToEdge]. */
    fun enableEdgeToEdge(top: Boolean = false, bottom: Boolean = false) {
        edgeToEdge = EdgeToEdge(top = top, bottom = bottom)
    }

    /** Draws both edges of this chatbox behind the system bars if [enabled], or none, see [EdgeToEdge]. */
    fun enableEdgeToEdge(enabled: Boolean) {
        edgeToEdge = if (enabled) EdgeToEdge.ALL else EdgeToEdge.NONE
    }

    /** @suppress */
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val insetsCompat = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        val systemInsets = insetsCompat.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
        )
        // From the window, not the dispatched insets, which a parent may have consumed
        // (e.g. Compose by its navigation bar), compared with the distance to the window bottom below
        val imeInsets = (ViewCompat.getRootWindowInsets(this) ?: insetsCompat)
            .getInsets(WindowInsetsCompat.Type.ime())
        // Before Android 11 or WebView 140, the page does not inset itself by the navigation bar
        val reportsSafeArea = CrispWebViewSupport.reportsSafeArea(context)

        // 1. Calculate how far this view's bottom edge is from the window/screen bottom:
        val viewLocationOnScreen = IntArray(2)
        getLocationOnScreen(viewLocationOnScreen)
        val viewBottomOnScreen = viewLocationOnScreen[1] + height
        val screenHeight = rootView.height
        // Distance between the bottom of CrispChatboxView and the bottom of the screen
        // (e.g. if bottomNav is 80px high, distanceToScreenBottom is 80px)
        val distanceToScreenBottom = (screenHeight - viewBottomOnScreen).coerceAtLeast(0)

        // 2. Only pad by the amount the IME overlaps THIS view:
        val bottomInsets = if (imeInsets.bottom > 0) {
            // Keyboard height minus whatever is already below this view
            (imeInsets.bottom - distanceToScreenBottom).coerceAtLeast(0)
        } else if (!reportsSafeArea && edgeToEdge.bottom) {
            systemInsets.bottom
        } else {
            0
        }

        setPadding(
            systemInsets.left,
            0,
            systemInsets.right,
            bottomInsets
        )

        val consumedTopInset = if (edgeToEdge.top) 0 else systemInsets.top
        val consumedBottomInset = if (imeInsets.bottom > 0) {
            imeInsets.bottom
        } else if (!reportsSafeArea && edgeToEdge.bottom) {
            systemInsets.bottom
        } else if (edgeToEdge.bottom) {
            0
        } else {
            systemInsets.bottom
        }

        val childViewInsetsCompat =
            insetsCompat.inset(0, consumedTopInset, 0, consumedBottomInset)
        val childViewInsets = childViewInsetsCompat.toWindowInsets() ?: insets

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.dispatchApplyWindowInsets(childViewInsets)
        }

        return insets
    }

    // endregion

    // region Sheet drag gesture

    private val sheetDragDetector = CrispSheetDragDetector(context).apply {
        // Loading and error views do not scroll, only the page does once shown
        contentScrolls = { webView != null && store.state.value is CrispChatboxState.Ready }
    }

    // Set by the sheet hosts (Fragment and Compose),
    // to swipe the chatbox down from anywhere and dismiss them
    internal var sheetDragHost: CrispSheetDragHost? = null
        set(value) {
            if (field !== value) {
                field = value
                sheetDragDetector.listener = value?.let(::SheetDragListener)
            }
        }

    // Forwards the drag to the host, which moves its sheet, then dismisses it or settles it back
    private inner class SheetDragListener(private val host: CrispSheetDragHost) :
        CrispSheetDragListener {
        override fun onDragStart(): Float {
            webView?.isMovingWithSheet = true
            // As the iOS sheet, rather than moving the sheet under the keyboard
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(windowToken, 0)
            // Maybe grabbed again while moving once released: dragged from there
            return host.onDragStart()
        }

        override fun onDrag(offsetY: Float) = host.onDrag(offsetY)

        override fun onDragEnd(offsetY: Float, velocityX: Float, velocityY: Float) {
            // Still moving when dismissed, until removed
            host.onRelease(offsetY, velocityX, velocityY) { webView?.isMovingWithSheet = false }
        }
    }

    // Detected before dispatching rather than when intercepting,
    // to see the whole gesture even if a child disallows it
    /** @suppress */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val wasDragging = sheetDragDetector.isDragging
        if (!sheetDragDetector.onTouchEvent(event)) {
            return super.dispatchTouchEvent(event)
        }
        if (!wasDragging) {
            // The content stops receiving the gesture taken by the drag
            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
        }
        return true
    }

    // endregion

    // region WebView

    // Whether the WebView was created (or failed to be), see scheduleWebViewCreation
    private var isWebViewCreated = false
    private var webViewCreationListener: OneShotPreDrawListener? = null
    private val webViewCreation = Runnable {
        webViewCreationListener = null
        isWebViewCreated = true
        webView = createWebView()?.also(::addWebView)
        loadIfNeeded()
    }

    /**
     * Whether the host is animating this view in, e.g. a sheet sliding up from the main thread,
     * which would stall while the WebView is created: it is created once done.
     */
    internal var isAnimatingIn = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    cancelWebViewCreation()
                } else {
                    loadIfNeeded()
                }
            }
        }

    /**
     * Creates the WebView right after the next frame, once needed to load.
     *
     * The first WebView of the process takes long to create, as it starts the WebView provider:
     * this view, with its loading view, is shown before rather than delayed.
     */
    private fun scheduleWebViewCreation() {
        if (isAnimatingIn || webViewCreationListener != null) return
        // Posted, to run once the frame is drawn
        webViewCreationListener = doOnPreDraw { post(webViewCreation) }
        // Drawn again, even if nothing else changes
        invalidate()
    }

    private fun cancelWebViewCreation() {
        webViewCreationListener?.removeListener()
        webViewCreationListener = null
        removeCallbacks(webViewCreation)
    }

    // Null when the device has no WebView provider, creating one would crash (see CrispWebViewSupport)
    private fun createWebView(): CrispWebView? = try {
        CrispWebView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            setupWebView(store, ::recoverWebView)
            onOverscrollTop = { sheetDragDetector.onContentOverscrollTop() }
        }
    } catch (e: Exception) {
        null
    }

    private fun addWebView(webView: CrispWebView) {
        // Below the native views
        addView(webView, 0)
        ViewCompat.requestApplyInsets(this)
        webView.clientBridge?.let {
            controllerDispatcher.attach(
                CrispControllerDispatcher.Chatbox(
                    it,
                    store.config,
                    store.state
                ) { load() }
            )
        }
        if (isAttachedToWindow) {
            webView.clientBridge?.let(::attachController)
        }
    }

    private fun removeWebView(webView: CrispWebView) {
        cancelSessionPreparation()
        uploadManager.dismiss()
        permissionManager.dismiss()
        cancelLoadingTimeout()
        webView.clientBridge?.let(controller::detach)
        webView.clientBridge?.let(controllerDispatcher::detach)
        removeView(webView)
        webView.destroy()
        this.webView = null
    }

    /**
     * Replaces the [webView] whose renderer is gone, as it cannot be used anymore.
     *
     * Reloads if the system killed it to reclaim memory (once started, not to reclaim it again in background),
     * but lets the user retry if it crashed, so that a renderer crashing on load does not loop.
     */
    private fun recoverWebView(didCrash: Boolean) {
        // First, so that calls rejected by the removed WebView wait instead of being made again in the new blank one
        store.onError(CrispClientException.RenderProcessGone(didCrash))
        webView?.let(::removeWebView)
        webView = createWebView()?.also(::addWebView)
        if (!didCrash) {
            load()
        }
    }

    // endregion

    // region Loading

    // The config last passed to load(config), even if invalid, to reload only when it changes
    internal val config: CrispConfig? get() = store.config.value
    private var pendingConfig: CrispConfig? = null
    private val sessions: CrispSessions by lazy { CrispSessions.from(context) }
    private val controllerDispatcher: CrispControllerDispatcher by lazy {
        CrispControllerDispatcher.get(
            context
        )
    }

    // Whether pendingConfig must be (re)loaded, done once attached to a window and started
    private var needsLoad = false

    /**
     * Loads the chatbox with [config][CrispConfig], replacing the one loaded before if any,
     * e.g. to switch to another website.
     *
     * Pending [controller] calls are canceled if [config][CrispConfig] loads another [session][im.crisp.sdk.api.CrispSession] than the previous one
     * (another `websiteId` or `tokenId`), or else made once reloaded, except those made at most once
     * (see [CrispChatboxController]), which fail if dispatched to the previous page.
     */
    fun load(config: CrispConfig) {
        cancelSessionPreparation()
        // First, so that the controller cancels the calls made for the previous config
        store.onConfigLoaded(config)
        val isSheet = config.presentation == Presentation.sheet
        loadingView.setCancelable(isSheet)
        clientErrorView.setCancelable(isSheet)

        if (!isSheet) {
            setBackgroundResource(R.color.crisp_sdk_chatbox_background)
        } else {
            background = null
        }

        if (config.websiteId.isBlank()) {
            // Drops the previous config, not to load it once attached or started again
            pendingConfig = null
            needsLoad = false
            cancelLoadingTimeout()
            webView?.clear()
            store.onError(CrispClientException.WebsiteMissing())
        } else {
            pendingConfig = config
            load()
        }
    }

    /** Requests a (re)load, done right away if possible, or else once attached and started. */
    private fun load() {
        // Nothing to reload without a valid config (e.g. a blank website ID)
        if (pendingConfig == null) return
        needsLoad = true
        // Loading until reloaded, even if deferred, so that calls wait instead of reaching the current page
        store.onPageStarted()
        loadIfNeeded()
    }

    private fun loadIfNeeded() {
        val config = pendingConfig ?: return
        // Only load once collectors and listeners are active: attached to a window and started.
        // Both are needed, as a detached view keeps its (possibly started) lifecycle.
        if (!needsLoad || !isAttachedToWindow || !isStarted()) return
        // Loads once created, still loading meanwhile
        if (!isWebViewCreated) {
            scheduleWebViewCreation()
            return
        }
        needsLoad = false
        // The page being replaced cannot be prepared anymore
        cancelSessionPreparation()
        store.onPageStarted()
        val mustReset = sessions.onLoad(config)
        store.holdNextReady { prepareSessionThenReady(config, mustReset) }
        cancelLoadingTimeout()
        startLoadingTimeout()
        try {
            (webView ?: throw CrispClientException.WebViewUnavailable()).load(config)
        } catch (e: CrispClientException) {
            cancelLoadingTimeout()
            store.onError(e)
        }
    }

    private var loadingTimeoutJob: Job? = null

    private fun startLoadingTimeout() {
        val lifecycle = observedLifecycle ?: return
        val timeoutDuration =
            if (context.isDebuggable) LOADING_TIMEOUT_DEBUG else LOADING_TIMEOUT_RELEASE
        loadingTimeoutJob = lifecycle.coroutineScope.launch {
            val readyState = withTimeoutOrNull(timeoutDuration) {
                store.state.first { it is CrispChatboxState.Ready }
            }
            if (readyState == null) {
                cancelSessionPreparation()
                webView?.clear()
                store.onError(CrispClientException.LoadingTimeout())
            }
        }
    }

    private fun cancelLoadingTimeout() {
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = null
    }

    /** Prepares the session before the page becomes ready, see [prepareSessionThenReady] */
    private var sessionPreparationJob: Job? = null

    /**
     * Prepares the [session][im.crisp.sdk.api.CrispSession] of [config][CrispConfig] once the page is loaded,
     * holding it not ready meanwhile, so that controller calls reach the prepared session:
     * - resets it, then reloads the page to start the new session, if [mustReset]
     *   (restored for a website loaded without its previous `tokenId`) or if requested through
     *   a controller,
     * - otherwise dispatches the calls queued for it by the controllers, then becomes ready.
     *
     * Becomes ready without being reset if it fails, to retry on the next load.
     */
    private fun prepareSessionThenReady(config: CrispConfig, mustReset: Boolean) {
        val bridge = webView?.clientBridge
        val scope = observedLifecycle?.coroutineScope
        if (bridge == null || scope == null) {
            store.onPageFinished()
            return
        }
        sessionPreparationJob = scope.launch {
            val result = if (mustReset) {
                CrispControllerDispatcher.Result.RESET_PENDING
            } else {
                controllerDispatcher.dispatch(config, bridge)
            }
            when (result) {
                CrispControllerDispatcher.Result.DISPATCHED -> {
                    sessionPreparationJob = null
                    store.onPageFinished()
                }

                CrispControllerDispatcher.Result.RESET_PENDING -> resetSessionThenReload(
                    config,
                    bridge
                )
                // Replaced by another page, which prepares it on its own
                CrispControllerDispatcher.Result.PAGE_UNLOADED -> Unit
            }
        }
    }

    private suspend fun resetSessionThenReload(config: CrispConfig, bridge: CrispClientBridge) {
        try {
            bridge.reset()
            sessions.onSessionReset(config.websiteId)
            controllerDispatcher.onSessionReset(config)
            // First, not to cancel this job while reloading
            sessionPreparationJob = null
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JSEvaluationException.PageUnloaded) {
            // Replaced by another page, which prepares it on its own
        } catch (e: Exception) {
            // Retried on the next load
            log(Level.WARN, LOG_TAG, "Cannot reset the session", e)
            sessionPreparationJob = null
            store.onPageFinished()
        }
    }

    private fun cancelSessionPreparation() {
        sessionPreparationJob?.cancel()
        sessionPreparationJob = null
    }

    // endregion

    // region State and Events

    // Listener for hosts (Fragment and Compose sheets)
    internal var onEventListener: ((CrispChatboxEvent) -> Unit)? = null
    private var collectionJob: Job? = null

    private fun startStateEventCollection() {
        val lifecycle = observedLifecycle ?: return
        collectionJob = lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The user may have updated WebView meanwhile (Play Store, Settings), so try to reload
                if ((store.state.value as? CrispChatboxState.Error)?.error is CrispClientException.WebViewUnsupported) {
                    needsLoad = true
                }
                // Launch state collection
                launch {
                    store.state.collect { state ->
                        loadingView.fadeVisible(state is CrispChatboxState.Loading)
                        val isError = state is CrispChatboxState.Error
                        val isWebsiteMissing =
                            isError && state.error is CrispClientException.WebsiteMissing
                        sdkErrorView.fadeVisible(isWebsiteMissing)
                        clientErrorView.fadeVisible(isError && !isWebsiteMissing)
                        if (state is CrispChatboxState.Error) {
                            clientErrorView.setError(state.error)
                        }
                        if (state is CrispChatboxState.Ready) {
                            webView?.fixSafeArea(edgeToEdge)
                        }
                    }
                }
                // Launch events collection
                launch {
                    store.events.collect { event ->
                        when (event) {
                            is CrispChatboxEvent.Alert -> context.showAlertDialog(event.message)
                            is CrispChatboxEvent.Close -> onEventListener?.invoke(event)
                            is CrispChatboxEvent.OpenExternalUrl -> openExternalUrl(
                                context,
                                event.url
                            )

                            is CrispChatboxEvent.Permission -> permissionManager.requestWebPermissions(
                                event.request
                            )

                            is CrispChatboxEvent.PermissionCanceled -> permissionManager.onPermissionRequestCanceled(
                                event.request
                            )

                            is CrispChatboxEvent.ShowCopyMenu -> showCopyMenu(
                                event.text,
                                event.x,
                                event.y
                            )

                            is CrispChatboxEvent.Upload -> uploadManager.showChooser(event.filePathCallback)
                        }
                    }
                }
                // Collectors are active, load what was requested meanwhile (first load, reload)
                loadIfNeeded()
            }
        }
    }

    private fun cancelStateEventCollection() {
        collectionJob?.cancel()
        collectionJob = null
    }

    // endregion

    // region XML attributes

    // Last, as initializers and init blocks run in their order in the class:
    // the edgeToEdge and load() state set before would be reset by the initializers declared after
    init {
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(
                attrs,
                R.styleable.CrispChatboxView,
                defStyleAttr,
                0
            )
            try {
                val websiteId =
                    typedArray.getString(R.styleable.CrispChatboxView_crisp_sdk_website_id)
                val tokenId = typedArray.getString(R.styleable.CrispChatboxView_crisp_sdk_token_id)
                val edgeToEdgeFlags = typedArray.getInt(
                    R.styleable.CrispChatboxView_crisp_sdk_edge_to_edge,
                    EdgeToEdge.NONE_ATTR_VALUE
                )
                edgeToEdge = EdgeToEdge(
                    top = (edgeToEdgeFlags and EdgeToEdge.TOP_ATTR_VALUE) != 0,
                    bottom = (edgeToEdgeFlags and EdgeToEdge.BOTTOM_ATTR_VALUE) != 0
                )
                if (websiteId != null) {
                    load(CrispConfig(websiteId = websiteId, tokenId = tokenId))
                }
            } finally {
                typedArray.recycle()
            }
        }
    }

    // endregion
}
