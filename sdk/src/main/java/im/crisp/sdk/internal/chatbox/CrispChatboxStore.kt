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

    /**
     * Whether the page loads another session than the one of [config] for a moment,
     * e.g. to unregister its notifications: its events are not forwarded to the controller,
     * as they are not of the chatbox session.
     */
    internal var isHandingOver = false

    internal fun emitClientEvent(event: CrispClientEvent) {
        if (isHandingOver) return
        _clientEvents.tryEmit(event)
    }

    // endregion

    // region state

    private val _state = MutableStateFlow<CrispChatboxState>(CrispChatboxState.Loading)
    val state: StateFlow<CrispChatboxState> = _state.asStateFlow()

    /**
     * Whether the page is ready (its [ready][im.crisp.sdk.internal.bridge.InterfaceLifecycleEvent.State.READY] interface lifecycle event),
     * even while held not ready (see [holdNextReady]), until another page starts.
     *
     * The Web client only emits it once its session is joined
     * (`triggerSessionLoaded`, from the `session:state` the servers send once joined),
     * unless its socket is disabled (static mode),
     * so it tells that the session is joined while [isSocketConnected].
     * Not emitted again when the socket reconnects, joining it again.
     */
    private val _isPageReady = MutableStateFlow(false)
    val isPageReady: StateFlow<Boolean> = _isPageReady.asStateFlow()

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

    // Called by WebViewClient / WebChromeClient / Bridge
    internal fun onPageStarted() {
        onPageReady = null
        isHandingOver = false
        _isSocketConnected.value = false
        _isPageReady.value = false
        _state.value = CrispChatboxState.Loading
    }

    internal fun onPageFinished() {
        _isPageReady.value = true
        onPageReady?.let {
            onPageReady = null
            it()
            return
        }
        _state.value = CrispChatboxState.Ready
        emitClientEvent(CrispClientEvent.Loaded)
    }

    internal fun onError(error: Throwable) {
        _state.value = CrispChatboxState.Error(error)
    }

    // endregion
}