package im.crisp.sdk.internal.notification

import android.content.Context
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.chatbox.CrispLegacyStorage
import im.crisp.sdk.internal.environment.Level
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The notifications of a chatbox ([CrispChatboxView][im.crisp.sdk.ui.CrispChatboxView]),
 * driving [CrispNotificationSync] along its page:
 * - [handOverIfNeeded] before loading a session, see [CrispNotificationPolicy.handoverFrom],
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
) {
    private val sync: CrispNotificationSync by lazy { CrispNotificationSync(context, store, permissionManager) }

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
     * Hands the device over from the previous session of the website, if its notifications are registered
     * (see [CrispNotificationPolicy.handoverFrom]).
     * Loads it again in [webView] before loading [config][CrispConfig] (e.g. with another `tokenId`),
     * to unregister them from it, as the Crisp servers only unregister them from the session joined,
     * see [CrispNotificationSync].
     *
     * The page stays loading meanwhile, and its events are not forwarded to the controller.
     * The session of the pre-3.x SDK, if not saved by the Web client yet, is restored there,
     * then removed once joined, see [CrispNotificationSync.handoverConfig].
     *
     * Best effort, not to delay each load: if the handover fails (e.g. the session is not joined in time,
     * offline or blocked), the previous session is forgotten as unreachable, without retrying,
     * then [onFinished] loads [config][CrispConfig] anyway, see [CrispNotificationSync.dropHandover].
     *
     * Returns `false` if not needed, or if it cannot be made (e.g. without a bridge or if the page cannot be
     * loaded), so [config][CrispConfig] is loaded right away. Otherwise, [onFinished] is called once handed over,
     * unless the page is replaced meanwhile.
     */
    fun handOverIfNeeded(config: CrispConfig, webView: CrispWebView?, onFinished: () -> Unit): Boolean {
        val previous = sync.handoverFrom(config) ?: return false
        val bridge = webView?.clientBridge ?: return false
        val scope = scope() ?: return false
        val handover = sync.handoverConfig(config, previous, legacyStorage::sessionId)
        log(Level.INFO, LOG_TAG, "Loading the previous session first to unregister its notifications")
        store.isHandingOver = true
        // Never ready meanwhile, so that the controllers do not call it
        store.holdNextReady {}
        try {
            webView.load(handover)
        } catch (e: CrispClientException) {
            log(Level.WARN, LOG_TAG, "Cannot load the previous session to unregister its notifications", e)
            store.isHandingOver = false
            sync.dropHandover(config, previous)
            return false
        }
        handoverJob = scope.launch {
            val isJoined = try {
                sync.handOver(config, previous, bridge, timeout())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(Level.WARN, LOG_TAG, "Cannot unregister the notifications of the previous session", e)
                sync.dropHandover(config, previous)
                // Replaced by another page, which loads on its own
                if (e is JSEvaluationException.PageUnloaded) return@launch
                false
            }
            // Saved by the Web client once joined, so restored from its storage from now on
            if (isJoined && handover.sessionId != null) legacyStorage.remove(config.websiteId)
            // First, not to cancel this job while loading
            handoverJob = null
            onFinished()
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
     * see [CrispChatboxStore.sessionId]), and once notifications are allowed or blocked meanwhile
     * (see [notificationsAllowed]), canceling what was done for the previous one.
     */
    fun start(config: CrispConfig, bridge: CrispClientBridge) {
        val scope = scope() ?: return
        notificationJob?.cancel()
        notificationsAllowed.value = sync.canShow()
        notificationJob = scope.launch {
            combine(store.sessionId.filterNotNull(), notificationsAllowed, ::Pair)
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
