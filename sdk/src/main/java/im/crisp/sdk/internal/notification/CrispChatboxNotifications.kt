package im.crisp.sdk.internal.notification

import android.content.Context
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.chatbox.CrispLegacyStorage
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.isOnlineNow
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
import kotlinx.coroutines.flow.update
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
    // Whether the device is online, checked before a handover,
    // which is only loaded while started (in foreground)
    private val isOnline: () -> Boolean = { context.isOnlineNow() },
    createSync: () -> CrispNotificationSync = { CrispNotificationSync(context, store) },
    createPrompter: () -> CrispNotificationPrompter = { CrispNotificationPrompter(context, store, permissionManager) },
    // The state of the prompt of a session, see start
    private val createPrompt: () -> CrispNotificationPrompt = { CrispNotificationPrompt() },
) {
    private val sync: CrispNotificationSync by lazy(createSync)

    private val prompter: CrispNotificationPrompter by lazy(createPrompter)

    /** Loads the session of the notifications to unregister before the chatbox one, see [handOverIfNeeded] */
    private var handoverJob: Job? = null

    /** Registers the notifications of the session of the page once ready, see [start] */
    private var notificationJob: Job? = null

    /**
     * Incremented to check again whether the app can show the notifications of the website (see [start]),
     * e.g. once resumed, as the user can allow them outside of the chatbox (or turn the channel of the website
     * on or off), e.g. in the system settings or from a permission request of the app.
     * Unlike the camera or microphone, asked when needed,
     * the token is only registered once loaded otherwise.
     */
    private val allowedChecks = MutableStateFlow(0)

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
            try {
                // Registers the token now allowed
                prompter.onPickerClick(config, event, ::checkAllowed)
            } catch (e: Exception) {
                log(Level.WARN, LOG_TAG, "Cannot handle the notification prompt", e)
            }
        }
    }

    /** Checks again whether notifications are allowed, registering or unregistering the token if it changed. */
    fun checkAllowed() {
        allowedChecks.update { it + 1 }
    }

    /** Stops what was done for the page replaced. */
    fun cancel() {
        handoverJob?.cancel()
        handoverJob = null
        stop()
    }

    /** Stops registering the notifications of the page, see [start]. */
    private fun stop() {
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
        // Never ready meanwhile, so that the controllers do not call it
        store.onPageStarted(CrispChatboxStore.PageMode.UNREGISTERING)
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
     * While offline, or if no session is joined in time (e.g. in background, or blocked,
     * which also blocks the new sessions), [onFailed] fails the load, keeping the previous session
     * to hand it over on the next load (e.g. once retried): [config][CrispConfig] could not be joined either,
     * and once loaded, the previous session could not be reached anymore.
     * Also if its token is not unregistered in time (e.g. a page not responding),
     * the whole handover being timed out, as the chatbox stays loading meanwhile, without its loading timeout.
     * So the previous session never prevents loading [config][CrispConfig] once a session can be joined:
     * it is then forgotten, unregistered if joined, see [CrispNotificationSync.handOver].
     *
     * If its page cannot be loaded (e.g. the WebView unsupported), [onFailed] fails the load with that error,
     * keeping the previous session too: the error does not depend on the session,
     * so [config][CrispConfig] could not be loaded either.
     * If the handover fails unexpectedly, the previous session is forgotten as unreachable,
     * see [CrispNotificationSync.dropHandover]. Not if replaced meanwhile: the next page hands it over again.
     *
     * Returns `false` if not needed, or if it cannot be made (e.g. without a bridge),
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
        if (!isOnline()) {
            log(Level.WARN, LOG_TAG, "Offline: cannot unregister the notifications of the previous session first")
            onFailed(CrispClientException.Offline())
            return true
        }
        val handover = sync.handoverConfig(config, previous, legacyStorage::sessionId)
        log(Level.INFO, LOG_TAG, "Loading the previous session first to unregister its notifications")
        // Never ready meanwhile, so that the controllers do not call it
        store.onPageStarted(CrispChatboxStore.PageMode.HANDOVER)
        try {
            load(handover)
        } catch (e: CrispClientException) {
            // Not specific to the previous session (WebView, assets), so the chatbox could not load either:
            // kept to hand it over on the next load
            log(Level.WARN, LOG_TAG, "Cannot load the previous session to unregister its notifications", e)
            // Not started, so back to the chatbox page, failing
            store.onPageStarted()
            onFailed(e)
            return true
        }
        handoverJob = scope.launch {
            // `null` once failed unexpectedly, the previous session being forgotten
            val isJoined = try {
                withTimeoutOrNull(timeout()) { sync.handOver(config, previous, bridge, store.awaitJoined()) } != null
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
            if (isJoined == true && handover.sessionId != null) legacyStorage.removeSaved(config.websiteId)
            // First, not to cancel this job while loading
            handoverJob = null
            if (isJoined == false) {
                log(
                    Level.WARN,
                    LOG_TAG,
                    "Previous session not joined or not unregistered in time, handed over on next load",
                )
                onFailed(if (isOnline()) CrispClientException.LoadingTimeout() else CrispClientException.Offline())
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
     * then prompts the user to enable them, see [CrispNotificationPrompter.promptWhenNeeded].
     *
     * Again for each session the page joins (e.g. reset in the page, purged by an operator,
     * see [CrispChatboxStore.sessionId]), once notifications are allowed or blocked meanwhile
     * (checked again by [checkAllowed]), and once enabled or disabled for the website
     * ([CrispNotifications.setEnabled][im.crisp.sdk.api.notification.CrispNotifications.setEnabled]),
     * canceling what was done for the previous one.
     *
     * Also removes the notification of its session while the chat is shown, see [onChatOpened].
     */
    fun start(config: CrispConfig, bridge: CrispClientBridge) {
        val scope = scope() ?: return
        notificationJob?.cancel()
        notificationJob = scope.launch {
            // The prompt of the session joined, kept while syncSession restarts for it
            // (e.g. notifications allowed meanwhile), not to forget a message sent
            var prompt: Pair<String, CrispNotificationPrompt>? = null
            fun promptOf(sessionId: String): CrispNotificationPrompt =
                prompt?.takeIf { it.first == sessionId }?.second
                    ?: createPrompt().also { prompt = sessionId to it }

            launch {
                combine(store.sessionId.filterNotNull(), isChatOpened, ::Pair)
                    .filter { (_, isOpened) -> isOpened }
                    .collect { (sessionId, _) -> sync.dismissShown(config, sessionId) }
            }
            // First, not to miss the messages sent while registering
            launch {
                store.clientEvents.filterIsInstance<CrispClientEvent.MessageSent>().collect {
                    store.sessionId.value?.let { promptOf(it).onMessageSent() }
                }
            }
            combine(
                store.sessionId.filterNotNull(),
                sync.enabledChanges(config),
                allowedChecks,
            ) { sessionId, isEnabled, _ ->
                // Allowed checked along enabled, so that enabling them syncs once, not again once resumed.
                // Only while enabled, not to check Firebase for an app not using notifications (see canShow)
                Triple(sessionId, isEnabled, isEnabled && sync.canShow(config))
            }
                .distinctUntilChanged()
                .collectLatest { (sessionId, _, _) -> syncSession(config, bridge, promptOf(sessionId)) }
        }
    }

    /** Registers or unregisters the notifications of the session joined, then prompts with [prompt], see [start]. */
    private suspend fun syncSession(config: CrispConfig, bridge: CrispClientBridge, prompt: CrispNotificationPrompt) {
        try {
            // Scoped, so that their exceptions are caught below
            coroutineScope {
                // Alongside, as the token saved is registered without waiting for it, and canceled with the sync
                launch { sync.refreshToken(config) }
                sync.onPageLoaded(config, bridge)
                launch { sync.followDeviceToken(config, bridge) }
                prompter.promptWhenNeeded(config, bridge, prompt)
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
        stop()
        sync.beforeReset(config, bridge, timeout())
    }

    // endregion

    private companion object {
        private const val LOG_TAG = "CrispNotifications"
    }
}
