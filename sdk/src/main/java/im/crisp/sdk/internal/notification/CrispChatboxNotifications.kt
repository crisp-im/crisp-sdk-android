package im.crisp.sdk.internal.notification

import android.content.Context
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.chatbox.CrispLegacyStorage
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.isAppInForegroundAndOnlineNow
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.permission.CrispPermissionManager
import im.crisp.sdk.internal.webview.CrispClientException
import im.crisp.sdk.internal.webview.CrispWebView
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The notifications of a chatbox ([CrispChatboxView][im.crisp.sdk.ui.CrispChatboxView]),
 * driving [CrispNotificationSync] along its page:
 * - [unregisterBeforeReplacing] before loading another config in the page, see [CrispNotificationPolicy.handoverFrom],
 * - [handOverIfNeeded] before loading a session, if not unregistered before,
 * - [start] once the page is ready,
 * - [beforeReset] before its session is reset,
 * - [cancel] once the page is replaced.
 *
 * Main thread only, as the view.
 */
internal class CrispChatboxNotifications(
    context: Context,
    private val store: CrispChatboxStore,
    private val permissionManager: CrispPermissionManager,
    private val legacyStorage: CrispLegacyStorage,
    // The scope of the lifecycle of the view, `null` while it has none
    private val scope: () -> CoroutineScope?,
    // How long the page may take to join its session
    private val timeout: () -> Duration,
    // Whether the page can connect its socket: online and in foreground
    private val canConnect: () -> Boolean = { context.isAppInForegroundAndOnlineNow() },
    createSync: () -> CrispNotificationSync = { CrispNotificationSync(context, store, permissionManager) },
) {
    private val sync: CrispNotificationSync by lazy(createSync)

    /** Loads the session of the notifications to unregister before the chatbox one, see [handOverIfNeeded] */
    private var handoverJob: Job? = null

    /** Registers the notifications of the session of the page once ready, see [start] */
    private var notificationJob: Job? = null

    /**
     * Whether the app can show notifications, checked again once resumed,
     * as the user can allow them outside of the chatbox,
     * e.g. in the system settings or from a permission request of the app.
     * Unlike the camera or microphone, asked when needed,
     * the token is only registered once loaded otherwise.
     */
    private val notificationsAllowed = MutableStateFlow(false)

    /**
     * Whether the chat is shown to the user (see [onChatOpened]),
     * to remove the notification of its session, read there.
     */
    private val isChatOpened = MutableStateFlow(false)

    /**
     * The chat is shown to the user, or not anymore.
     * Once shown with its session joined, the notification of that session is removed, if any.
     */
    fun onChatOpened(isOpened: Boolean) {
        isChatOpened.value = isOpened
    }

    /** Handles the choices of the notification prompt in the page of [bridge]. */
    fun attach(bridge: CrispClientBridge) {
        bridge.onPickerClick { event ->
            val config = store.config.value ?: return@onPickerClick
            val scope = scope() ?: return@onPickerClick
            scope.launch {
                try {
                    // Registers the token now allowed
                    sync.onPickerClick(config, event, ::checkAllowed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(Level.WARN, LOG_TAG, "Cannot handle the notification prompt", e)
                }
            }
        }
    }

    /** Checks again whether notifications are allowed, registering or unregistering the token if it changed. */
    fun checkAllowed() {
        if (notificationJob != null) notificationsAllowed.value = sync.canShow()
    }

    /** Stops what was done for the page replaced. */
    fun cancel() {
        handoverJob?.cancel()
        handoverJob = null
        notificationJob?.cancel()
        notificationJob = null
    }

    // region Handover

    /**
     * Before replacing the page of [bridge][CrispClientBridge] by one loading [next][CrispConfig] (another `tokenId`),
     * unregisters the notifications of the session it joined,
     * if they are to hand over (see [CrispNotificationPolicy.handoverFrom]),
     * so that it is not loaded again for a handover once replaced (see [handOverIfNeeded]).
     *
     * The chatbox is loading meanwhile, and the events of the page replaced are not forwarded to the controller.
     * If not unregistered within [timeout] (e.g. a page not responding), the session is left to hand over.
     *
     * Returns `false` if not needed, or if the page did not join that session (e.g. disconnected),
     * so [next][CrispConfig] is loaded right away. Otherwise, [onFinished] loads it once unregistered,
     * unless the page is replaced meanwhile.
     */
    fun unregisterBeforeReplacing(next: CrispConfig, bridge: CrispClientBridge, onFinished: () -> Unit): Boolean {
        val previous = sync.handoverFrom(next) ?: return false
        // Read before the page is started again below, which forgets them
        if (store.sessionId.value != previous.sessionId || !store.isSocketConnected.value) return false
        val scope = scope() ?: return false
        log(Level.INFO, LOG_TAG, "Unregistering the notifications of the session joined before loading another one")
        store.onPageStarted()
        store.isHandingOver = true
        // Never ready meanwhile, so that the controllers do not call it
        store.holdNextReady {}
        handoverJob = scope.launch {
            try {
                withTimeoutOrNull(timeout()) { sync.unregisterBeforeReplacing(next, previous, bridge) }
                    ?: log(Level.WARN, LOG_TAG, "Notifications not unregistered in time, handed over once replaced")
            } catch (e: JSEvaluationException.PageUnloaded) {
                log(Level.WARN, LOG_TAG, "Page unloaded before unregistering the notifications, handed over once replaced")
            }
            // First, not to cancel this job while loading
            handoverJob = null
            onFinished()
        }
        return true
    }

    /**
     * Hands the device over from the previous session of the website,
     * if its notifications are registered (see [CrispNotificationPolicy.handoverFrom]).
     * Loads it again in [webView][CrispWebView] before loading [config][CrispConfig] (e.g. with another `tokenId`),
     * to unregister them from it, as the Crisp servers only unregister them from the session joined,
     * see [CrispNotificationSync].
     *
     * The page stays loading meanwhile, and its events are not forwarded to the controller.
     * The session of the pre-3.x SDK, if not saved by the Web client yet, is restored there,
     * then removed once joined, see [CrispNotificationSync.handoverConfig].
     *
     * While it cannot connect (offline or in background), or if no session is joined in time
     * (e.g. blocked, which also blocks the new sessions), [onFailed] fails the load, keeping the previous session
     * to hand it over on the next load (e.g. once retried): [config][CrispConfig] could not be joined either,
     * and once loaded, the previous session could not be reached anymore.
     * So the previous session never prevents loading [config][CrispConfig] once a session can be joined:
     * it is then forgotten, unregistered if joined, see [CrispNotificationSync.handOver].
     *
     * If its page cannot be loaded, the previous session is forgotten as unreachable,
     * see [CrispNotificationSync.dropHandover]. Not if replaced meanwhile: the next page hands it over again.
     *
     * Returns `false` if not needed, or if it cannot be made
     * (e.g. without a bridge or if the page cannot be loaded),
     * so [config][CrispConfig] is loaded right away.
     * Otherwise, [onFinished] or [onFailed] is called once handed over, unless the page is replaced meanwhile.
     */
    fun handOverIfNeeded(
        config: CrispConfig,
        webView: CrispWebView?,
        onFinished: () -> Unit,
        onFailed: (CrispClientException) -> Unit,
    ): Boolean = webView != null && handOverIfNeeded(config, webView.clientBridge, webView::load, onFinished, onFailed)

    /**
     * @see [handOverIfNeeded], loading the page of [bridge][CrispClientBridge] with [load],
     * which throws a [CrispClientException] if it cannot.
     */
    internal fun handOverIfNeeded(
        config: CrispConfig,
        bridge: CrispClientBridge?,
        load: (CrispConfig) -> Unit,
        onFinished: () -> Unit,
        onFailed: (CrispClientException) -> Unit,
    ): Boolean {
        val previous = sync.handoverFrom(config) ?: return false
        bridge ?: return false
        val scope = scope() ?: return false
        if (!canConnect()) {
            log(Level.WARN, LOG_TAG, "Offline: cannot unregister the notifications of the previous session first")
            onFailed(CrispClientException.Offline())
            return true
        }
        val handover = sync.handoverConfig(config, previous, legacyStorage::sessionId)
        log(Level.INFO, LOG_TAG, "Loading the previous session first to unregister its notifications")
        store.isHandingOver = true
        // Never ready meanwhile, so that the controllers do not call it
        store.holdNextReady {}
        try {
            load(handover)
        } catch (e: CrispClientException) {
            log(Level.WARN, LOG_TAG, "Cannot load the previous session to unregister its notifications", e)
            store.isHandingOver = false
            sync.dropHandover(config, previous)
            return false
        }
        handoverJob = scope.launch {
            // `null` once failed unexpectedly, the previous session being forgotten
            val isJoined = try {
                sync.handOver(config, previous, bridge, timeout())
            } catch (e: CancellationException) {
                throw e
            } catch (e: JSEvaluationException.PageUnloaded) {
                // Replaced by another page, which loads on its own, handing the previous session over again
                handoverJob = null
                return@launch
            } catch (e: Exception) {
                log(Level.WARN, LOG_TAG, "Cannot unregister the notifications of the previous session", e)
                sync.dropHandover(config, previous)
                null
            }
            // Saved by the Web client once joined, so restored from its storage from now on
            if (isJoined == true && handover.sessionId != null) legacyStorage.remove(config.websiteId)
            // First, not to cancel this job while loading
            handoverJob = null
            if (isJoined == false) {
                onFailed(if (canConnect()) CrispClientException.LoadingTimeout() else CrispClientException.Offline())
            } else {
                onFinished()
            }
        }
        return true
    }

    // endregion

    // region Session

    /**
     * Once the page of [config][CrispConfig] is ready (its session reset first if requested),
     * registers the notifications of its session, or unregisters them,
     * then prompts the user to enable them, see [CrispNotificationSync.promptWhenNeeded].
     *
     * Again for each session the page joins (e.g. reset in the page, purged by an operator,
     * see [CrispChatboxStore.sessionId]), once notifications are allowed or blocked meanwhile
     * (see [notificationsAllowed]), and once enabled or disabled for the website
     * ([CrispNotifications.setEnabled][im.crisp.sdk.api.notification.CrispNotifications.setEnabled]),
     * canceling what was done for the previous one.
     *
     * Also removes the notification of its session while the chat is shown, see [onChatOpened].
     */
    fun start(config: CrispConfig, bridge: CrispClientBridge) {
        val scope = scope() ?: return
        notificationJob?.cancel()
        notificationsAllowed.value = sync.canShow()
        notificationJob = scope.launch {
            launch {
                combine(store.sessionId.filterNotNull(), isChatOpened, ::Pair)
                    .filter { (_, isOpened) -> isOpened }
                    .collect { (sessionId, _) -> sync.dismissShown(config, sessionId) }
            }
            combine(
                store.sessionId.filterNotNull(),
                notificationsAllowed,
                sync.enabledChanges(config),
                ::Triple,
            )
                .distinctUntilChanged()
                .collectLatest { syncSession(config, bridge) }
        }
    }

    /** Registers or unregisters the notifications of the session joined, see [start]. */
    private suspend fun syncSession(config: CrispConfig, bridge: CrispClientBridge) {
        try {
            // Scoped, so that their exceptions are caught below
            coroutineScope {
                val prompt = CrispNotificationPrompt()
                // First, not to miss the messages sent while registering
                val messagesSent = launch {
                    store.clientEvents.filterIsInstance<CrispClientEvent.MessageSent>().collect {
                        prompt.onMessageSent()
                    }
                }
                sync.onPageLoaded(config, bridge, timeout())
                launch { sync.followDeviceToken(config, bridge) }
                sync.promptWhenNeeded(config, bridge, prompt)
                messagesSent.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JSEvaluationException.PageUnloaded) {
            // Replaced by another page, which registers them on its own
        } catch (e: Exception) {
            // Retried on the next session joined or change of the permission
            log(Level.WARN, LOG_TAG, "Cannot update the notifications of the session", e)
        }
    }

    /**
     * Before resetting the session of [config][CrispConfig] loaded in the page of [bridge]:
     * stops registering its notifications, then unregisters them.
     */
    suspend fun beforeReset(config: CrispConfig, bridge: CrispClientBridge) {
        notificationJob?.cancel()
        notificationJob = null
        sync.beforeReset(config, bridge, timeout())
    }

    // endregion

    private companion object {
        private const val LOG_TAG = "CrispNotifications"
    }
}
