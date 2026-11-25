package im.crisp.sdk.internal.notification

import android.content.Context
import android.content.Intent
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers and unregisters the FID or FCM token of the device on the sessions loaded by a chatbox,
 * following [CrispNotificationPolicy]. [CrispNotificationPrompter] prompts the user to enable notifications.
 *
 * The Crisp servers silently drop `notification:provider:register` and `unregister`
 * (see `crisp-relay-socket-client` and `crisp-library-relay`):
 * - sent before the session is joined, so they are only sent once the session is loaded while its socket is connected
 *   (see [CrispChatboxStore.sessionId]), not to be buffered while disconnected,
 *   as they would be sent before joining again,
 * - once rate-limited (10 per 3 minutes per session and per IP).
 *
 * They are not acknowledged, nor are their updates of the session atomic, so:
 * - two updates of the same session are spaced, not to lose one (see [CrispNotificationSpacing]),
 * - a page can be replaced right after unregistering: the call returns once the Web client emitted it,
 *   which was enough in the tests on devices,
 * - tokens are registered again on each load, as it is idempotent.
 */
internal class CrispNotificationSync(
    private val context: Context,
    private val chatboxStore: CrispChatboxStore,
    private val store: CrispNotificationStore = CrispNotificationStore.get(context),
    // Whether the app can show the notifications of a website:
    // Firebase set up, notifications allowed by the user, and its channel not turned off
    private val canShowNotifications: (websiteId: String) -> Boolean = {
        CrispFirebase.isAvailable(context) &&
            CrispNotificationDisplay.areEnabled(context) &&
            CrispNotificationDisplay.isChannelEnabled(context, it)
    },
    // The FID or FCM token of the device
    private val fetchToken: suspend () -> String? = { CrispFirebase.fetchToken(context) },
    private val spacing: CrispNotificationSpacing = CrispNotificationSpacing.instance,
    // Removes the notification shown for a website
    private val cancelShown: (websiteId: String) -> Unit = { CrispNotificationDisplay.cancel(context, it) },
) {
    /**
     * Whether the app can show the notifications of the website of [config][CrispConfig]:
     * enabled, Firebase set up, notifications allowed by the user, and the channel of the website not turned off,
     * e.g. to register the token again once allowed outside of the chatbox.
     * Otherwise, the token is unregistered, as its notifications would be dropped.
     *
     * Enabled first, not to check Firebase (logging an error without it) for an app not using notifications.
     */
    fun canShow(config: CrispConfig): Boolean =
        store.record(config.websiteId).enabled && canShowNotifications(config.websiteId)

    /**
     * Whether notifications are enabled for the website of [config][CrispConfig], and its changes.
     * @see [CrispNotificationStore.enabledChanges]
     */
    fun enabledChanges(config: CrispConfig): Flow<Boolean> = store.enabledChanges(config.websiteId)

    /**
     * Removes the notification shown for the website of [config][CrispConfig],
     * once a chatbox shows [sessionId], if it is the recorded session, whose notifications are shown:
     * the user reads its conversation there.
     */
    fun dismissShown(config: CrispConfig, sessionId: String) {
        if (store.record(config.websiteId).session?.sessionId != sessionId) return
        cancelShown(config.websiteId)
    }

    // region Session joined

    /**
     * Once the page loaded with [config][CrispConfig] joined its session,
     * registers the token of the device on it,
     * or unregisters it (see [CrispNotificationPolicy.onSessionLoaded]).
     *
     * Started with the page, waits for its session to be joined, then returns once the calls are made.
     * Registers the token last saved right away, without waiting for Firebase, which does not time out (e.g. offline).
     * The token is fetched meanwhile by [refreshToken], and registered by [followDeviceToken] if it changed.
     * It usually did not, as `onNewToken` and `onRegistered` save it, even while no chatbox is loaded.
     */
    suspend fun onPageLoaded(config: CrispConfig, bridge: CrispClientBridge) {
        val sessionId = chatboxStore.awaitJoined()
        val record = store.record(config.websiteId)
        log(
            Level.DEBUG,
            LOG_TAG,
            "Session ${sessionId.short()} joined: enabled ${record.enabled}, " +
                "recorded ${record.session?.sessionId?.short()}, registered ${record.session?.notificationId?.short()}",
        )
        // Nothing to do for a website that never used notifications,
        // without fetching the FID or FCM token
        if (!record.enabled && record.session?.notificationId == null) return
        val deviceToken = if (record.enabled && canShowNotifications(config.websiteId)) {
            // Fetched meanwhile by refreshToken
            val token = store.deviceToken
            if (token == null && record.session?.sessionId == sessionId) {
                // Unknown rather than not allowed.
                // The one registered on this session (e.g. migrated from the pre-3.x SDK) is kept,
                // not to stop notifications before the token is fetched,
                // and replaced once fetched or received if it changed (see followDeviceToken)
                log(Level.DEBUG, LOG_TAG, "FCM token unknown yet, the session keeps the one registered, if any")
                return
            }
            // Another session is joined, recorded without token (the previous one being unreachable),
            // registered once fetched or received (see followDeviceToken)
            if (token == null) log(Level.DEBUG, LOG_TAG, "FCM token unknown yet, registered on the session once fetched")
            token
        } else {
            null
        }
        applyPlan(config, bridge, sessionId, deviceToken)
    }

    /**
     * Once the page of [config][CrispConfig] joined its session,
     * fetches the FID or FCM token of the device and saves it,
     * if notifications can be shown for its website (enabled, Firebase set up and allowed by the user).
     * [followDeviceToken] then registers it if it changed.
     *
     * Fetched on each sync, as the app may not pass it (e.g. its own service not calling `onNewToken`),
     * and as fetching registers the FID with FCM (see [CrispFirebase.fetchToken]).
     *
     * Run alongside [onPageLoaded], which registers the token last saved without waiting for it,
     * and canceled with it: Firebase does not time out (e.g. offline), so a fetch never outlives its sync.
     * Its result is then dropped, and fetched again by the next sync (cached by Firebase).
     */
    suspend fun refreshToken(config: CrispConfig) {
        chatboxStore.awaitJoined()
        if (!store.record(config.websiteId).enabled || !canShowNotifications(config.websiteId)) return
        fetchToken()?.let { store.deviceToken = it }
    }

    /**
     * Once the page of [config][CrispConfig] is [loaded][onPageLoaded],
     * registers the FID or FCM token of the device on its session each time it changes
     * (received by `onRegistered` or `onNewToken`), replacing the one registered,
     * rather than on the next load, not to lose the notifications sent meanwhile.
     *
     * Runs as long as the page. Only for the recorded session of the page,
     * if notifications can be shown and it is not revoked
     * (unregistered once reset, see [CrispNotificationPolicy.onSessionLoaded]).
     */
    suspend fun followDeviceToken(config: CrispConfig, bridge: CrispClientBridge) {
        store.deviceTokens.filterNotNull().collect { token ->
            val sessionId = chatboxStore.awaitJoined()
            val record = store.record(config.websiteId)
            val session = record.session?.takeIf { it.sessionId == sessionId } ?: return@collect
            if (!record.enabled || session.revoked || session.notificationId == token) return@collect
            if (!canShowNotifications(config.websiteId)) return@collect
            log(Level.DEBUG, LOG_TAG, "FID or FCM token changed to ${token.short()} on session ${sessionId.short()}")
            applyPlan(config, bridge, sessionId, token)
        }
    }

    /**
     * Unregisters or registers [deviceToken] on the joined [sessionId],
     * @see [CrispNotificationPolicy.onSessionLoaded].
     */
    private suspend fun applyPlan(
        config: CrispConfig,
        bridge: CrispClientBridge,
        sessionId: String,
        deviceToken: String?,
    ) {
        // Within the update, not to overwrite a change made meanwhile from another thread,
        // e.g. CrispNotifications.setEnabled or CrispSessionController.Session.reset.
        // Recorded before the calls, the calls being best effort (unacknowledged),
        // and registered again on the next load.
        // Except the token to unregister, kept until unregistered, so that it is unregistered again on the next
        // load if interrupted (e.g. the page replaced or closed while spaced), rather than left on the session.
        lateinit var plan: CrispNotificationPlan
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onSessionLoaded(record, sessionId, config.tokenId, deviceToken)
                .also { plan = it }
                .let { CrispNotificationPolicy.beforeCalls(it, sessionId) }
        }
        log(
            Level.DEBUG,
            LOG_TAG,
            "Plan for session ${sessionId.short()}: unregister ${plan.unregister?.short()}, " +
                "register ${plan.register?.short()}, unreachable ${plan.unreachable?.sessionId?.short()}",
        )
        plan.unreachable?.let { logUnreachable() }
        plan.unregister?.let {
            unregister(bridge, sessionId, it)
            store.update(config.websiteId) { record -> CrispNotificationPolicy.onUnregistered(record, plan, sessionId) }
        }
        plan.register?.let {
            warnIfNoMessagingService()
            register(bridge, sessionId, it)
        }
    }

    /**
     * Before the page resets its session, unregisters the token registered on it, if any, then forgets it.
     *
     * Waits for the session to be joined, up to [timeout].
     *
     * Whenever the token is not unregistered, the recorded session of its `tokenId` is
     * [revoked][CrispNotificationPolicy.revoke], keeping its token, not to show its notifications meanwhile.
     * Already revoked for a reset requested through a controller, but not for one made on load
     * (a website loaded without its previous `tokenId`). Then:
     * - not joined in time: returns, and the caller resets the session anyway, its token being left on it.
     *   The next session joined replaces it in the record, as unreachable (see [CrispNotificationPolicy.onSessionLoaded]),
     * - canceled (e.g. by the loading timeout of the chatbox,
     *   which elapses first for a reset made before it becomes ready) or the page replaced meanwhile
     *   ([JSEvaluationException.PageUnloaded][im.crisp.sdk.internal.bridge.js.JSEvaluationException.PageUnloaded] thrown):
     *   the session is not reset, and the reset stays requested, so the next page unregisters its token before resetting it.
     */
    suspend fun beforeReset(config: CrispConfig, bridge: CrispClientBridge, timeout: Duration) {
        if (store.record(config.websiteId).session?.notificationId == null) return
        var isUnregistered = false
        try {
            val sessionId = withTimeoutOrNull(timeout) { chatboxStore.awaitJoined() }
            if (sessionId == null) {
                log(Level.WARN, LOG_TAG, "Session not joined in time, reset without unregistering its FCM token")
                return
            }
            // Forgotten once unregistered, so that it is unregistered by the next page if this one is replaced before
            CrispNotificationPolicy.onBeforeReset(store.record(config.websiteId), sessionId).second
                ?.let { unregister(bridge, sessionId, it) }
            store.update(config.websiteId) { record -> CrispNotificationPolicy.onBeforeReset(record, sessionId).first }
            isUnregistered = true
        } finally {
            // Only once not unregistered on the Web (timed out, canceled or the page replaced)
            if (!isUnregistered) {
                store.update(config.websiteId) { record -> CrispNotificationPolicy.revoke(record, config.tokenId) }
            }
        }
    }

    // endregion

    // region Handover

    /**
     * The recorded session to hand the device over from before loading [config][CrispConfig].
     * @see [CrispNotificationPolicy.handoverFrom]
     */
    fun handoverFrom(config: CrispConfig): CrispNotificationSession? =
        CrispNotificationPolicy.handoverFrom(store.record(config.websiteId), config)

    /**
     * The [config][CrispConfig] loading [previous][CrispNotificationSession] again:
     * the website with the `tokenId` it was loaded with.
     *
     * Its `sessionId` is only injected if it is the session of the pre-3.x SDK still to restore
     * ([legacySessionId], see [CrispLegacyStorage.sessionId][im.crisp.sdk.internal.chatbox.CrispLegacyStorage.sessionId]),
     * as the Web client did not save it yet, so it would start another session.
     * Never from a notification, see [CrispNotificationPolicy].
     */
    fun handoverConfig(
        config: CrispConfig,
        previous: CrispNotificationSession,
        legacySessionId: (CrispConfig) -> String? = { null },
    ): CrispConfig {
        val handover = config.copy(tokenId = previous.tokenId, sessionId = null)
        return handover.copy(sessionId = legacySessionId(handover)?.takeIf { it == previous.sessionId })
    }

    /**
     * Hands the device over from [previous][CrispNotificationSession],
     * once the page loading it again joined [sessionId], unregisters its token there, then forgets it.
     * If the page joined another session, [previous] cannot be reached anymore, so it is forgotten too.
     * That is also the case of an unknown `sessionId` injected (e.g. of the pre-3.x SDK):
     * the servers do not join it, and the Web client creates another session right away.
     *
     * Kept if canceled before unregistering (e.g. timed out, see [CrispChatboxNotifications.handOverIfNeeded]),
     * or if the page is replaced meanwhile
     * ([JSEvaluationException.PageUnloaded][im.crisp.sdk.internal.bridge.js.JSEvaluationException.PageUnloaded], thrown),
     * to hand it over on the next load.
     */
    suspend fun handOver(
        config: CrispConfig,
        previous: CrispNotificationSession,
        bridge: CrispClientBridge,
        sessionId: String,
    ) {
        // Forgotten once unregistered, so that it is handed over again if the page is replaced before
        val token = CrispNotificationPolicy.onHandoverLoaded(store.record(config.websiteId), previous, sessionId).second
        token?.let { unregister(bridge, sessionId, it) } ?: logUnreachable()
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onHandoverLoaded(record, previous, sessionId).first
        }
    }

    /**
     * Before replacing the page that joined [previous][CrispNotificationSession] by one loading [config][CrispConfig] (another `tokenId`),
     * unregisters its token there, then forgets it, so that no handover is needed (see [handoverFrom]).
     *
     * Forgotten once the call returns, so that it is still handed over if canceled or if the page is unloaded before
     * ([JSEvaluationException.PageUnloaded][im.crisp.sdk.internal.bridge.js.JSEvaluationException.PageUnloaded], thrown).
     */
    suspend fun unregisterBeforeReplacing(
        config: CrispConfig,
        previous: CrispNotificationSession,
        bridge: CrispClientBridge,
    ) {
        previous.notificationId?.let { unregister(bridge, previous.sessionId, it) }
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onHandoverLoaded(record, previous, previous.sessionId).first
        }
    }

    /**
     * Forgets [previous][CrispNotificationSession] once its handover failed unexpectedly,
     * as unreachable.
     * Its notifications are no longer shown, but its `notificationId` is left on it.
     * @see [CrispNotificationPolicy.onSessionLoaded].
     */
    fun dropHandover(config: CrispConfig, previous: CrispNotificationSession) {
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onHandoverLoaded(record, previous, sessionId = null).first
        }
        logUnreachable()
    }

    private fun logUnreachable() = log(
        Level.WARN,
        LOG_TAG,
        "The previous session cannot be loaded anymore, its notifications are not shown, " +
            "but its FID or FCM token is left on it",
    )

    // endregion

    // region Calls

    /**
     * Logs once if no `FirebaseMessagingService` is declared in the app `AndroidManifest.xml`,
     * neither [CrispNotificationService][im.crisp.sdk.api.notification.CrispNotificationService] nor one of the app,
     * so that the notifications are dropped.
     * Only the base `FirebaseMessagingService` of `firebase-messaging` is then declared,
     * which ignores data messages.
     *
     * A service of the app that does not pass them to
     * [CrispNotifications.handleNotification][im.crisp.sdk.api.notification.CrispNotifications.handleNotification]
     * cannot be detected.
     */
    private fun warnIfNoMessagingService() {
        if (isNoServiceChecked.getAndSet(true)) return
        val services = try {
            // ResolveInfoFlags only from Android 13
            context.packageManager.queryIntentServices(
                Intent(MESSAGING_EVENT).setPackage(context.packageName),
                0,
            )
        } catch (e: RuntimeException) {
            return
        }
        if (services.none { it.serviceInfo?.name != FIREBASE_MESSAGING_SERVICE }) {
            log(
                Level.ERROR,
                LOG_TAG,
                "Notifications are not shown: no FirebaseMessagingService receives them. " +
                    "Declare im.crisp.sdk.api.notification.CrispNotificationService in AndroidManifest.xml, " +
                    "or pass them to CrispNotifications.handleNotification from your own FirebaseMessagingService",
            )
        }
    }

    private suspend fun register(bridge: CrispClientBridge, sessionId: String, notificationId: String) {
        spacing.await(sessionId)
        try {
            bridge.registerNotification(notificationId)
            log(
                Level.INFO,
                LOG_TAG,
                "FID or FCM token ${notificationId.short()} registered on session ${sessionId.short()}",
            )
        } catch (e: Exception) {
            // Replaced by another page: the caller handles it, e.g. not to forget the token
            if (e is CancellationException || e is JSEvaluationException.PageUnloaded) throw e
            log(Level.WARN, LOG_TAG, "Cannot register the FID or FCM token", e)
        }
    }

    private suspend fun unregister(bridge: CrispClientBridge, sessionId: String, notificationId: String) {
        spacing.await(sessionId)
        try {
            bridge.unregisterNotification(notificationId)
            log(
                Level.INFO,
                LOG_TAG,
                "FID or FCM token ${notificationId.short()} unregistered from session ${sessionId.short()}",
            )
        } catch (e: Exception) {
            // Replaced by another page: the caller handles it, e.g. not to forget the token
            if (e is CancellationException || e is JSEvaluationException.PageUnloaded) throw e
            log(Level.WARN, LOG_TAG, "Cannot unregister the FID or FCM token", e)
        }
    }

    // endregion

    companion object {
        private const val LOG_TAG = "CrispNotifications"

        // Checked once per process, see warnIfNoMessagingService
        private val isNoServiceChecked = AtomicBoolean(false)

        /** The action of the FCM messages, received by a `FirebaseMessagingService`. */
        private const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"

        /** The base service declared by `firebase-messaging`, which ignores data messages. */
        private const val FIREBASE_MESSAGING_SERVICE = "com.google.firebase.messaging.FirebaseMessagingService"
    }
}

/** The end of a session ID or token, to tell them apart in the logs without logging them. */
internal fun String.short(): String = "…" + takeLast(6)
