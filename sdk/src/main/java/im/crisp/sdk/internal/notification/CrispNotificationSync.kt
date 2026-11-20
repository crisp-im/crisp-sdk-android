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
import im.crisp.sdk.internal.bridge.types.ConversationMessageContent
import im.crisp.sdk.internal.bridge.types.GenericMessage
import im.crisp.sdk.internal.bridge.types.LocalMessage
import im.crisp.sdk.internal.bridge.types.LocalMessageFingerprint
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers and unregisters the FID or FCM token of the device on the sessions loaded by a chatbox,
 * following [CrispNotificationPolicy], and prompts the user to enable notifications.
 *
 * The Crisp servers silently drop `notification:provider:register` and `unregister`
 * (see `crisp-relay-socket-client` and `crisp-library-relay`):
 * - sent before the session is joined, so they are only sent once the page is ready while its socket is connected
 *   (see [CrispChatboxStore.isPageReady]), not to be buffered while disconnected,
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
    private val canShow: () -> Boolean = {
        CrispFirebase.isAvailable(context) && CrispNotificationDisplay.areEnabled(context)
    },
    // The FID or FCM token of the device
    private val fetchToken: suspend () -> String? = { CrispFirebase.fetchToken(context) },
    private val spacing: CrispNotificationSpacing = CrispNotificationSpacing.instance,
) {
    // region Session joined

    /**
     * The session joined by the page of [bridge][CrispClientBridge],
     * once it is ready while its socket is connected
     * (see [CrispChatboxStore.isPageReady][CrispChatboxStore._isPageReady]).
     * Needed before sending to the Crisp servers.
     */
    suspend fun awaitJoined(bridge: CrispClientBridge): String {
        combine(
            chatboxStore.isPageReady,
            chatboxStore.isSocketConnected
        ) { isReady, isConnected ->
            isReady && isConnected
        }.first { it }
        return bridge.getSessionIdentifier().rawValue
    }

    /**
     * The session loaded by the page of [bridge][CrispClientBridge], once it is ready,
     * even while its socket is disconnected: the Web client loads it before being ready.
     * Enough for what stays on the device.
     */
    private suspend fun awaitLoaded(bridge: CrispClientBridge): String {
        chatboxStore.isPageReady.first { it }
        return bridge.getSessionIdentifier().rawValue
    }

    /**
     * Once the page loaded with [config][CrispConfig] joined its session,
     * registers the token of the device on it,
     * or unregisters it (see [CrispNotificationPolicy.onSessionLoaded]).
     * Then prompts to enable notifications.
     *
     * Runs as long as the page, from the time it starts.
     * The token is fetched within [timeout], falling back to the one last saved,
     * as Firebase does not time out (e.g. offline).
     */
    suspend fun onPageLoaded(config: CrispConfig, bridge: CrispClientBridge, timeout: Duration) {
        val sessionId = awaitJoined(bridge)
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
        val deviceToken = if (record.enabled && canShow()) {
            // Wrapped, as `null` is also a failed fetch (logged by CrispFirebase)
            val fetched = withTimeoutOrNull(timeout) { Result.success(fetchToken()) }
            if (fetched == null) log(Level.WARN, LOG_TAG, "Timed out fetching the FCM token")
            val token = (fetched?.getOrNull() ?: store.deviceToken)?.also { store.deviceToken = it }
            if (token == null && record.session?.sessionId == sessionId) {
                // Unknown rather than not allowed.
                // The one registered on this session (e.g. migrated from the pre-3.x SDK) is kept,
                // not to stop notifications on a failed fetch, and updated on the next load
                log(Level.WARN, LOG_TAG, "Cannot get the FCM token, the session keeps the one registered, if any")
                promptIfNeeded(config, bridge, sessionId)
                return
            }
            // Another session is joined: recorded without token, the previous one being unreachable
            if (token == null) log(Level.WARN, LOG_TAG, "Cannot get the FCM token, not registered on the session")
            token
        } else {
            null
        }
        val plan = CrispNotificationPolicy.onSessionLoaded(
            store.record(config.websiteId),
            sessionId,
            config.tokenId,
            deviceToken,
        )
        store.update(config.websiteId) { plan.record }
        log(
            Level.DEBUG,
            LOG_TAG,
            "Plan for session ${sessionId.short()}: unregister ${plan.unregister?.short()}, " +
                "register ${plan.register?.short()}, unreachable ${plan.unreachable?.sessionId?.short()}",
        )
        plan.unreachable?.let { logUnreachable() }
        plan.unregister?.let { unregister(bridge, sessionId, it) }
        plan.register?.let {
            warnIfNoMessagingService()
            register(bridge, sessionId, it)
        }
        promptIfNeeded(config, bridge, sessionId)
    }

    /**
     * Before the page resets its session, unregisters the token registered on it, if any.
     *
     * Waits for the session to be joined, up to [timeout].
     * Once elapsed, the session is reset anyway, its token being left on it.
     */
    suspend fun beforeReset(config: CrispConfig, bridge: CrispClientBridge, timeout: Duration) {
        if (store.record(config.websiteId).session?.notificationId == null) return
        val sessionId = withTimeoutOrNull(timeout) { awaitJoined(bridge) }
        if (sessionId == null) {
            log(Level.WARN, LOG_TAG, "Session not joined in time, reset without unregistering its FCM token")
            return
        }
        var notificationId: String? = null
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onBeforeReset(record, sessionId).also { notificationId = it.second }.first
        }
        notificationId?.let { unregister(bridge, sessionId, it) }
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
     * Hands the device over from [previous]: once the page loading it again joins its session, unregisters its
     * token there, then forgets it.
     * If the page joined another session, [previous] cannot be reached anymore, so it is forgotten too.
     *
     * Best effort: if the session is not joined within [timeout] (e.g. offline, or blocked), [previous] is
     * forgotten too, as unreachable (see [dropHandover]), rather than retried, which would delay each load.
     *
     * Returns whether the page joined a session.
     */
    suspend fun handOver(
        config: CrispConfig,
        previous: CrispNotificationSession,
        bridge: CrispClientBridge,
        timeout: Duration,
    ): Boolean {
        val sessionId = withTimeoutOrNull(timeout) { awaitJoined(bridge) }
        if (sessionId == null) {
            log(Level.WARN, LOG_TAG, "The previous session was not joined in time to unregister its notifications")
            dropHandover(config, previous)
            return false
        }
        var token: String? = null
        store.update(config.websiteId) { record ->
            CrispNotificationPolicy.onHandoverLoaded(record, previous, sessionId).also { token = it.second }.first
        }
        token?.let { unregister(bridge, sessionId, it) } ?: logUnreachable()
        return true
    }

    /**
     * Forgets [previous][CrispNotificationSession] once its handover failed
     * (e.g. not joined in time, or not loaded), as unreachable.
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
     * Shows a message prompting the user to enable notifications, as the iOS SDK does, once the user sent a
     * message, if notifications are enabled for the website but not allowed by the user yet, and the user did not
     * answer it on this session.
     *
     * Only from Android 13, where notifications need the `POST_NOTIFICATIONS` runtime permission.
     */
    suspend fun promptIfNeeded(config: CrispConfig, bridge: CrispClientBridge, sessionId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val record = store.record(config.websiteId)
        val session = record.session?.takeIf { it.sessionId == sessionId } ?: return
        if (!record.enabled || session.promptAnswered || session.revoked) return
        if (!CrispFirebase.isAvailable(context) || isPermissionGranted()) return
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
        var messages = bridge.getMessages()
        if (messages.any { it.fingerprint == LocalMessageFingerprint.Remote(PROMPT_FINGERPRINT) }) return
        if (messages.none { it.from == MessageSender.USER }) return
        // The identity prompt of the Web client starts with the same "reply" header as a standalone prompt,
        // so it is only added without it. Shown a moment after the message sent, so it is waited for
        if (messages.none { it.fingerprint == IDENTITY_PROMPT_FINGERPRINT }) {
            messages = awaitIdentityPrompt(bridge)
            // Allowed or prompted meanwhile
            if (isPermissionGranted()) return
            if (messages.any { it.fingerprint == LocalMessageFingerprint.Remote(PROMPT_FINGERPRINT) }) return
        }
        val isStandalone = messages.none { it.fingerprint == IDENTITY_PROMPT_FINGERPRINT }
        log(Level.DEBUG, LOG_TAG, "Prompting to enable notifications on session ${sessionId.short()}, standalone $isStandalone")
        bridge.injectMessage(promptMessage(isStandalone))
    }

    /**
     * The messages once the identity prompt of the Web client is shown, or after [IDENTITY_PROMPT_DELAY] if not
     * (e.g. disabled, or the user email or phone is known).
     */
    private suspend fun awaitIdentityPrompt(bridge: CrispClientBridge): List<LocalMessage> {
        log(Level.DEBUG, LOG_TAG, "Waiting up to $IDENTITY_PROMPT_DELAY for the identity prompt of the Web client")
        return withTimeoutOrNull(IDENTITY_PROMPT_DELAY) {
            var messages: List<LocalMessage>
            do {
                delay(IDENTITY_PROMPT_POLLING)
                messages = bridge.getMessages()
            } while (messages.none { it.fingerprint == IDENTITY_PROMPT_FINGERPRINT })
            messages
        } ?: bridge.getMessages()
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
        bridge: CrispClientBridge,
        event: PickerClickEvent,
        onGranted: () -> Unit,
    ) {
        if (event.fingerprint != PROMPT_FINGERPRINT) return
        val loaded = awaitLoaded(bridge)
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

    @SuppressLint("InlinedApi") // Only checked from Android 13, see promptIfNeeded
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
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            log(Level.WARN, LOG_TAG, "Cannot unregister the FID or FCM token", e)
        }
    }

    // endregion

    companion object {
        private const val LOG_TAG = "CrispNotifications"

        /** Of the injected prompt, the same as on iOS. */
        val PROMPT_FINGERPRINT = MessageFingerprint(-10006)

        /** Of the identity prompt of the Web client, after which the prompt is not standalone. */
        private val IDENTITY_PROMPT_FINGERPRINT = LocalMessageFingerprint.Local("\$identity")

        /**
         * How long the identity prompt of the Web client is waited for after a message sent.
         *
         * `crisp-client` shows it 5s after the first message sent by a user without email nor phone
         * (`ui = 5e3` in `checkUserIdentity`, called by `handleSent`, in `chat-alert-*.js`),
         * if `email_visitors` or `phone_visitors` is set, right away if `force_identify` is,
         * or later while a bot is typing. Plus a margin for the dispatch: check it on each `crisp-client` update.
         */
        private val IDENTITY_PROMPT_DELAY = 6.seconds

        /** Between two checks of the messages for the identity prompt of the Web client. */
        private val IDENTITY_PROMPT_POLLING = 500.milliseconds

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
