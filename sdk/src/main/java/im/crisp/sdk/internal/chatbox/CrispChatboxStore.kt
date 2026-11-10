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
    private val _state = MutableStateFlow<CrispChatboxState>(CrispChatboxState.Loading)
    val state: StateFlow<CrispChatboxState> = _state.asStateFlow()

    // The config last requested, even if invalid or not loaded yet, which pending controller calls are made for
    private val _config = MutableStateFlow<CrispConfig?>(null)
    val config: StateFlow<CrispConfig?> = _config.asStateFlow()

    private val _events = Channel<CrispChatboxEvent>(Channel.BUFFERED)
    val events: Flow<CrispChatboxEvent> = _events.receiveAsFlow()

    // Unlike events, only delivered to current collectors (the attached controller), dropped otherwise
    private val _clientEvents = MutableSharedFlow<CrispClientEvent>(extraBufferCapacity = 64)
    val clientEvents: SharedFlow<CrispClientEvent> = _clientEvents.asSharedFlow()

    // Called by CrispChatboxView
    // Whether the socket of the page is connected, from the Web client logs
    private val _isSocketConnected = MutableStateFlow(false)
    val isSocketConnected: StateFlow<Boolean> = _isSocketConnected.asStateFlow()

    internal fun onConfigLoaded(config: CrispConfig) {
        _config.value = config
    }

    // Run instead of becoming ready when the page is, see holdNextReady
    private var onPageReady: (() -> Unit)? = null

    /**
     * Runs [action] instead of becoming ready when the next page is, e.g. to reset its session first.
     * [action] then calls [onPageFinished] to become ready, or reloads. Dropped when another page starts.
     */
    internal fun holdNextReady(action: () -> Unit) {
        onPageReady = action
    }

    // Called by WebViewClient / WebChromeClient / Bridge
    internal fun onPageStarted() {
        onPageReady = null
        _isSocketConnected.value = false
        _state.value = CrispChatboxState.Loading
    }

    internal fun onPageFinished() {
        onPageReady?.let {
            onPageReady = null
            it()
            return
        }
        _state.value = CrispChatboxState.Ready
        emitClientEvent(CrispClientEvent.Loaded)
    }

    internal fun onSocketStateChanged(isConnected: Boolean) {
        _isSocketConnected.value = isConnected
    }

    internal fun emitClientEvent(event: CrispClientEvent) {
        _clientEvents.tryEmit(event)
    }

    internal fun onError(error: Throwable) {
        _state.value = CrispChatboxState.Error(error)
    }

    internal fun emitEvent(event: CrispChatboxEvent) {
        scope.launch {
            _events.send(event)
        }
    }
}