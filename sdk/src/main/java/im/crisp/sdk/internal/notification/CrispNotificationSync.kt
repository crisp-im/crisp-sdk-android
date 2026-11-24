package im.crisp.sdk.internal.notification

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.PickerClickEvent
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.bridge.types.ConversationMessageContent
import im.crisp.sdk.internal.bridge.types.GenericMessage
import im.crisp.sdk.internal.bridge.types.Message
import im.crisp.sdk.internal.bridge.types.MessageFingerprint
import im.crisp.sdk.internal.bridge.types.MessageSender
import im.crisp.sdk.internal.bridge.types.Origin
import im.crisp.sdk.internal.bridge.types.Participant
import im.crisp.sdk.internal.bridge.types.PickerId
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.permission.CrispPermissionIntent
import im.crisp.sdk.internal.permission.CrispPermissionManager
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers and unregisters the FID or FCM token of the device on the sessions loaded by a chatbox,
 * following [CrispNotificationPolicy], and prompts the user to enable notifications.
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
    private val permissionManager: CrispPermissionManager,
    private val store: CrispNotificationStore = CrispNotificationStore.get(context),
    // Whether the app can show notifications: Firebase set up and notifications allowed by the user
    private val canShowNotifications: () -> Boolean = {
        CrispFirebase.isAvailable(context) && CrispNotificationDisplay.areEnabled(context)
    },
    // The FID or FCM token of the device
    private val fetchToken: suspend () -> String? = { CrispFirebase.fetchToken(context) },
    private val spacing: CrispNotificationSpacing = CrispNotificationSpacing.instance,
    // Removes the notification shown for a website
    private val cancelShown: (websiteId: String) -> Unit = { CrispNotificationDisplay.cancel(context, it) },
) {
    /**
     * Whether the app can show notifications: Firebase set up and notifications allowed by the user,
     * e.g. to register the token again once allowed outside of the chatbox.
     */
    fun canShow(): Boolean = canShowNotifications()

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
     * The session joined by the page, while its socket is connected (see [CrispChatboxStore.sessionId]).
     * Needed before sending to the Crisp servers.
     */
    suspend fun awaitJoined(): String = combine(
        chatboxStore.sessionId,
        chatboxStore.isSocketConnected,
    ) { sessionId, isConnected ->
        sessionId?.takeIf { isConnected }
    }.filterNotNull().first()

    /**
     * The session loaded by the page, even while its socket is disconnected.
     * Enough for what stays on the device.
     */
    private suspend fun awaitLoaded(): String = chatboxStore.sessionId.filterNotNull().first()

    /**
     * Once the page loaded with [config][CrispConfig] joined its session,
     * registers the token of the device on it,
     * or unregisters it (see [CrispNotificationPolicy.onSessionLoaded]).
     *
     * Runs as long as the page, from the time it starts.
     * The token is fetched within [timeout], falling back to the one last saved,
     * as Firebase does not time out (e.g. offline).
     */
    suspend fun onPageLoaded(config: CrispConfig, bridge: CrispClientBridge, timeout: Duration) {
        val sessionId = awaitJoined()
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
        val deviceToken = if (record.enabled && canShowNotifications()) {
            // Wrapped, as `null` is also a failed fetch (logged by CrispFirebase)
            val fetched = withTimeoutOrNull(timeout) { Result.success(fetchToken()) }
            if (fetched == null) log(Level.WARN, LOG_TAG, "Timed out fetching the FCM token")
            val token = (fetched?.getOrNull() ?: store.deviceToken)?.also { store.deviceToken = it }
            if (token == null && record.session?.sessionId == sessionId) {
                // Unknown rather than not allowed.
                // The one registered on this session (e.g. migrated from the pre-3.x SDK) is kept,
                // not to stop notifications on a failed fetch,
                // and updated on the next load or once received (see followDeviceToken)
                log(Level.WARN, LOG_TAG, "Cannot get the FCM token, the session keeps the one registered, if any")
                return
            }
            // Another session is joined: recorded without token, the previous one being unreachable
            if (token == null) log(Level.WARN, LOG_TAG, "Cannot get the FCM token, not registered on the session")
            token
        } else {
            null
        }
        applyPlan(config, bridge, sessionId, deviceToken)
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
            val sessionId = awaitJoined()
            val record = store.record(config.websiteId)
            val session = record.session?.takeIf { it.sessionId == sessionId } ?: return@collect
            if (!record.enabled || session.revoked || session.notificationId == token || ! canShowNotifications()) return@collect
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
     * Kept if the page is replaced meanwhile ([JSEvaluationException.PageUnloaded][im.crisp.sdk.internal.bridge.js.JSEvaluationException.PageUnloaded], thrown),
     * for the next page to unregister it before resetting the session.
     *
     * Waits for the session to be joined, up to [timeout].
     * Once elapsed, the session is reset anyway, its token being left on it.
     */
    suspend fun beforeReset(config: CrispConfig, bridge: CrispClientBridge, timeout: Duration) {
        if (store.record(config.websiteId).session?.notificationId == null) return
        val sessionId = withTimeoutOrNull(timeout) { awaitJoined() }
        if (sessionId == null) {
            log(Level.WARN, LOG_TAG, "Session not joined in time, reset without unregistering its FCM token")
            return
        }
        // Forgotten once unregistered, so that it is unregistered by the next page if this one is replaced before
        CrispNotificationPolicy.onBeforeReset(store.record(config.websiteId), sessionId).second
            ?.let { unregister(bridge, sessionId, it) }
        store.update(config.websiteId) { record -> CrispNotificationPolicy.onBeforeReset(record, sessionId).first }
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
     * Hands the device over from [previous][CrispNotificationSession].
     * Once the page loading it again joins its session, unregisters its token there, then forgets it.
     * If the page joined another session, [previous] cannot be reached anymore, so it is forgotten too.
     * That is also the case of an unknown `sessionId` injected (e.g. of the pre-3.x SDK):
     * the servers do not join it, and the Web client creates another session right away.
     *
     * If no session is joined within [timeout], [previous] is kept, to hand it over on the next load:
     * the page cannot join any session (e.g. offline, in background, or blocked, which also blocks the new sessions,
     * whatever their `tokenId`), so the session loaded next could not be joined either.
     *
     * Kept too if the page is replaced meanwhile
     * ([JSEvaluationException.PageUnloaded][im.crisp.sdk.internal.bridge.js.JSEvaluationException.PageUnloaded], thrown).
     *
     * Returns whether the page joined a session.
     */
    suspend fun handOver(
        config: CrispConfig,
        previous: CrispNotificationSession,
        bridge: CrispClientBridge,
        timeout: Duration,
    ): Boolean {
        val sessionId = withTimeoutOrNull(timeout) { awaitJoined() }
        if (sessionId == null) {
            log(Level.WARN, LOG_TAG, "No session joined in time, the previous session will be handed over on next load")
            return false
        }
        // Forgotten once unregistered, so that it is handed over again if the page is replaced before
        val token = CrispNotificationPolicy.onHandoverLoaded(store.record(config.websiteId), previous, sessionId).second
        token?.let { unregister(bridge, sessionId, it) } ?: logUnreachable()
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onHandoverLoaded(record, previous, sessionId).first
        }
        return true
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
     * Forgets [previous][CrispNotificationSession] once its handover failed (e.g. its page cannot be loaded),
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

    // region Prompt

    /**
     * Shows a message prompting the user to enable notifications, as the iOS SDK and SDK 2.x do,
     * if notifications are enabled for the website but not allowed by the user yet,
     * and the user did not answer it on this session: below the identity request of the Web client,
     * or standalone once the user sent a message, see [CrispNotificationPrompt] (told of the messages sent).
     *
     * Only from Android 13, where notifications need the `POST_NOTIFICATIONS` runtime permission.
     *
     * Runs as long as the page, until shown or not needed anymore,
     * checking the page every [CrispNotificationPrompt.POLLING] while its session is joined.
     */
    suspend fun promptWhenNeeded(config: CrispConfig, bridge: CrispClientBridge, prompt: CrispNotificationPrompt) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!CrispFirebase.isAvailable(context)) return
        if (!isPermissionDeclared()) {
            if (isUndeclaredLogged.compareAndSet(false, true)) {
                log(
                    Level.ERROR,
                    LOG_TAG,
                    "Cannot ask for notifications: android.permission.POST_NOTIFICATIONS must be declared " +
                        "in AndroidManifest.xml",
                )
            }
            return
        }
        while (true) {
            // Not while disconnected, e.g. in background
            val sessionId = awaitJoined()
            val session = promptableSession(config, sessionId) ?: return
            val decision = prompt.decide(bridge.getIdentityPromptState(PROMPT_FINGERPRINT), session.promptShown)
            if (decision == CrispNotificationPrompt.Decision.WAIT) {
                delay(CrispNotificationPrompt.POLLING)
                continue
            }
            if (decision != CrispNotificationPrompt.Decision.DONE) {
                val isStandalone = decision == CrispNotificationPrompt.Decision.SHOW_STANDALONE
                log(
                    Level.DEBUG,
                    LOG_TAG,
                    "Prompting to enable notifications on session ${sessionId.short()}, standalone $isStandalone",
                )
                bridge.injectMessage(promptMessage(isStandalone))
                // Shown again on the next loads until answered
                store.update(config.websiteId) { record ->
                    val shown = record.session?.takeIf { it.sessionId == sessionId } ?: return@update record
                    record.copy(session = shown.copy(promptShown = true))
                }
            }
            return
        }
    }

    /** The recorded session of the joined [sessionId] if the prompt can be shown,
     * not answered and not allowed yet.
     */
    private fun promptableSession(config: CrispConfig, sessionId: String): CrispNotificationSession? {
        val record = store.record(config.websiteId)
        val session = record.session?.takeIf { it.sessionId == sessionId } ?: return null
        return session.takeIf { record.enabled && !it.promptAnswered && !it.revoked && !isPermissionGranted() }
    }

    /**
     * Handles the choice of the user in the prompt, asking for the permission if accepted,
     * then calling [onGranted] (e.g. to register the `notificationId`).
     *
     * Right away, even while the socket is disconnected: the choices of the prompt have a handler,
     * so the Web client does not update the message on the Crisp servers
     * (see `onMessagePickerButtonClick` of `crisp-client`).
     */
    suspend fun onPickerClick(
        config: CrispConfig,
        event: PickerClickEvent,
        onGranted: () -> Unit,
    ) {
        if (event.fingerprint != PROMPT_FINGERPRINT) return
        val loaded = awaitLoaded()
        log(Level.DEBUG, LOG_TAG, "Prompt answered on session ${loaded.short()}: ${event.choiceValue}")
        store.update(config.websiteId) { record ->
            val session = record.session?.takeIf { it.sessionId == loaded } ?: return@update record
            record.copy(session = session.copy(promptAnswered = true))
        }
        when (event.choiceValue) {
            CHOICE_ENABLE -> permissionManager.requestNativePermissions(CrispPermissionIntent.NOTIFICATIONS) {
                if (it && isPermissionGranted()) onGranted()
            }

            CHOICE_SKIP -> Unit
            else -> log(Level.WARN, LOG_TAG, "Unknown choice '${event.choiceValue}' in the notification prompt")
        }
    }

    private fun isPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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

    @SuppressLint("InlinedApi") // Only checked from Android 13, see promptWhenNeeded
    private fun isPermissionDeclared(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun promptMessage(isStandalone: Boolean): Message {
        val ask = context.getString(R.string.crisp_sdk_prompt_notification_ask)
        // crisp-client ships both strings as plain text, so the emphasis is applied here, as on iOS
        val text = if (isStandalone) {
            "**${context.getString(R.string.crisp_sdk_prompt_notification_reply)}**\n_${ask}_"
        } else {
            "_${ask}_"
        }
        return Message(
            generic = GenericMessage(
                content = ConversationMessageContent.PickerContent(
                    id = PickerId(""),
                    text = text,
                    choices = listOf(
                        ConversationMessageContent.PickerContent.Choice(
                            label = context.getString(R.string.crisp_sdk_action_notifications_enable),
                            selected = false,
                            value = CHOICE_ENABLE,
                        ),
                        ConversationMessageContent.PickerContent.Choice(
                            label = context.getString(R.string.crisp_sdk_action_deny),
                            selected = false,
                            value = CHOICE_SKIP,
                        ),
                    ),
                    required = false,
                ),
                origin = Origin.CHAT,
                timestamp = System.currentTimeMillis(),
                from = MessageSender.OPERATOR,
                user = Participant(nickname = "System"),
                automated = true,
            ),
            fingerprint = PROMPT_FINGERPRINT,
        )
    }

    // endregion

    // region Calls

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

        /** Of the injected prompt, the same as on iOS. */
        val PROMPT_FINGERPRINT = MessageFingerprint(-10006)

        private const val CHOICE_ENABLE = "enable"
        private const val CHOICE_SKIP = "skip"

        private val isUndeclaredLogged = AtomicBoolean(false)

        // Checked once per process, see warnIfNoMessagingService
        private val isNoServiceChecked = AtomicBoolean(false)

        /** The action of the FCM messages, received by a `FirebaseMessagingService`. */
        private const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"

        /** The base service declared by `firebase-messaging`, which ignores data messages. */
        private const val FIREBASE_MESSAGING_SERVICE = "com.google.firebase.messaging.FirebaseMessagingService"

        /** The end of a session ID or token, to tell them apart in the logs without logging them. */
        private fun String.short(): String = "…" + takeLast(6)
    }
}
