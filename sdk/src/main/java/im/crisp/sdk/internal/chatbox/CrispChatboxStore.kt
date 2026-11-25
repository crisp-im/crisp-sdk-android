package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.api.CrispConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal class CrispChatboxStore(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    // region config

    // The config last requested, even if invalid or not loaded yet, which pending controller calls are made for
    private val _config = MutableStateFlow<CrispConfig?>(null)
    val config: StateFlow<CrispConfig?> = _config.asStateFlow()

    internal fun onConfigLoaded(config: CrispConfig) {
        _config.value = config
    }

    // endregion

    // region internal events

    private val _events = Channel<CrispChatboxEvent>(Channel.BUFFERED)
    val events: Flow<CrispChatboxEvent> = _events.receiveAsFlow()

    internal fun emitEvent(event: CrispChatboxEvent) {
        scope.launch {
            _events.send(event)
        }
    }

    // endregion

    // region public events

    // Unlike events, only delivered to current collectors (the attached controller), dropped otherwise
    private val _clientEvents = MutableSharedFlow<CrispClientEvent>(extraBufferCapacity = 64)
    val clientEvents: SharedFlow<CrispClientEvent> = _clientEvents.asSharedFlow()

    /** Dropped unless the page is of the chatbox session, see [PageMode]. */
    internal fun emitClientEvent(event: CrispClientEvent) {
        if (pageMode != PageMode.CHATBOX) return
        _clientEvents.tryEmit(event)
    }

    // endregion

    // region state

    /**
     * What the page is loaded for, set when it starts (see [onPageStarted]).
     * Only a [chatbox][PageMode.CHATBOX] page becomes ready and forwards its events to the controller:
     * the others hold another session for a moment, to unregister its notifications,
     * so the controller calls of the chatbox session must not reach them.
     */
    internal enum class PageMode {
        /** The session of [config]. */
        CHATBOX,

        /**
         * The previous session of the website loaded again before the session of [config],
         * to unregister its notifications there,
         * see [CrispChatboxNotifications.handOverIfNeeded][im.crisp.sdk.internal.notification.CrispChatboxNotifications.handOverIfNeeded].
         */
        HANDOVER,

        /**
         * The page of the previous config, kept for a moment to unregister the notifications of its session
         * before loading [config], see
         * [CrispChatboxNotifications.unregisterBeforeReplacing][im.crisp.sdk.internal.notification.CrispChatboxNotifications.unregisterBeforeReplacing].
         */
        UNREGISTERING,
    }

    var pageMode = PageMode.CHATBOX
        private set

    private val _state = MutableStateFlow<CrispChatboxState>(CrispChatboxState.Loading)
    val state: StateFlow<CrispChatboxState> = _state.asStateFlow()

    /**
     * The session the page joined (its `session:loaded` event), until another page starts,
     * whatever its [pageMode], even while held not ready (see [holdNextReady]).
     *
     * The Web client emits it once the servers sent the `session:state` of the session joined
     * (`triggerSessionLoaded`), so it tells that the session is joined while [isSocketConnected].
     * Not emitted again when the socket reconnects, joining it again,
     * but emitted again with the new session when the Web client resets it in the page
     * (e.g. purged by an operator, `session:request:purge`), without loading another page.
     * `null` in static mode, as there is no session: the Web client switches to it (`runStatic`)
     * once its main socket cannot connect and falls back to the rescue one (`socket:rescued`),
     * if its interface is not created yet. Never on start, as `<crisp-widget>` sets `lock_maximized`.
     *
     * It can be emitted before or after the [ready][im.crisp.sdk.internal.bridge.InterfaceLifecycleEvent.State.READY]
     * interface lifecycle event: after it for a session restored (the chat is initialized from its state first),
     * before it for a new one, so the page still becomes ready on the latter.
     */
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    // The session [CrispClientEvent.Loaded] was emitted for on this page, not to emit it twice
    private var loadedSessionId: String? = null

    /**
     * Run instead of becoming ready when the page is.
     *
     * @see [holdNextReady]
     */
    private var onPageReady: (() -> Unit)? = null

    /**
     * Runs [action] instead of becoming ready when the next page is, e.g. to reset its session first.
     * [action] then calls [onPageFinished] to become ready, or reloads. Dropped when another page starts.
     */
    internal fun holdNextReady(action: () -> Unit) {
        onPageReady = action
    }

    // Called by CrispChatboxView
    // Whether the socket of the page is connected, from the Web client logs
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    internal fun onSocketStateChanged(isConnected: Boolean) {
        _isSocketConnected.value = isConnected
    }

    /**
     * The session joined by the page, once its socket is connected (see [sessionId]).
     * Needed before sending to the Crisp servers, which drop what is sent before joining.
     */
    suspend fun awaitJoined(): String =
        combine(sessionId, isSocketConnected) { sessionId, isConnected -> sessionId?.takeIf { isConnected } }
            .filterNotNull()
            .first()

    // Called by WebViewClient / WebChromeClient / Bridge

    /**
     * A page starts loading for [mode], forgetting the state of the previous one.
     * Also for a page kept for another [mode], e.g. [PageMode.UNREGISTERING].
     */
    internal fun onPageStarted(mode: PageMode = PageMode.CHATBOX) {
        onPageReady = null
        pageMode = mode
        _isSocketConnected.value = false
        _sessionId.value = null
        loadedSessionId = null
        _state.value = CrispChatboxState.Loading
    }

    /**
     * The page is ready (its [ready][im.crisp.sdk.internal.bridge.InterfaceLifecycleEvent.State.READY]
     * interface lifecycle event), or the page prepared by [holdNextReady] can become ready.
     * Then emits [CrispClientEvent.Loaded] once its session is known, see [onSessionLoaded].
     *
     * Never ready, nor held, unless the page is of the chatbox session (see [PageMode]),
     * even once the Web client is ready again, as it resets its session in the page
     * (e.g. purged by an operator during a handover).
     */
    internal fun onPageFinished() {
        if (pageMode != PageMode.CHATBOX) return
        onPageReady?.let {
            onPageReady = null
            it()
            return
        }
        _state.value = CrispChatboxState.Ready
        emitLoadedIfNeeded()
    }

    /**
     * The page joined [sessionId] (its `session:loaded` event, see [sessionId][CrispChatboxStore.sessionId]),
     * notified once ready, or right away if ready already, e.g. reset in the page.
     */
    internal fun onSessionLoaded(sessionId: String?) {
        _sessionId.value = sessionId
        if (_state.value is CrispChatboxState.Ready) emitLoadedIfNeeded()
    }

    private fun emitLoadedIfNeeded() {
        val sessionId = _sessionId.value ?: return
        if (sessionId == loadedSessionId) return
        loadedSessionId = sessionId
        emitClientEvent(CrispClientEvent.Loaded(sessionId))
    }

    internal fun onError(error: Throwable) {
        _state.value = CrispChatboxState.Error(error)
    }

    // endregion
}