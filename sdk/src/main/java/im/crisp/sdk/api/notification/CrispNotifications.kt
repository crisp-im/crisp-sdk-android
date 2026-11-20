package im.crisp.sdk.api.notification

import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder
import com.google.firebase.messaging.RemoteMessage
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.notification.CrispNotificationDisplay
import im.crisp.sdk.internal.notification.CrispNotificationListeners
import im.crisp.sdk.internal.notification.CrispNotificationPayload
import im.crisp.sdk.internal.notification.CrispNotificationPolicy
import im.crisp.sdk.internal.notification.CrispNotificationStore

/**
 * Notifies the user of the replies of your operators, with Firebase Cloud Messaging (FCM).
 * See the [im.crisp.sdk.api.notification] package to set them up.
 *
 * - [setEnabled] opts a website in, [isEnabled] tells whether it is.
 * - From your own `FirebaseMessagingService` ([CrispNotificationService] does it otherwise):
 *   [isCrispNotification] and [handleNotification] show the Crisp notifications,
 *   [onNewToken] and [onRegistered] pass the identifier of the device.
 * - [configFrom] gives the chatbox of a notification tapped, to show it yourself
 *   with `opensChatbox = false`, and [isCrispIntent] tells whether an `Intent` comes from one.
 * - [addListener] and [removeListener] notify you of the notifications shown.
 *
 * Only the notifications of the last session loaded for a website are shown and opened,
 * so that the conversations of a user are never shown to another one using the device,
 * see the package for which session is notified.
 */
object CrispNotifications {
    private const val LOG_TAG = "CrispNotifications"

    // region Settings

    /**
     * Enables notifications for [websiteId], or disables them. Disabled by default, persisted.
     *
     * Can be called at any time, e.g. once the user opts-in, or from `Application.onCreate`.
     * The token is registered on (or unregistered from) the session of the next chatbox loading [websiteId].
     * Disabling them stops showing them right away, and removes the one shown.
     *
     * The notification settings of SDK 2.x (`Crisp.enableNotifications`) are migrated.
     */
    @JvmStatic
    fun setEnabled(context: Context, websiteId: String, enabled: Boolean) {
        if (websiteId.isBlank()) {
            log(Level.WARN, LOG_TAG, "Cannot enable notifications: the websiteId is missing")
            return
        }
        CrispNotificationStore.get(context).update(websiteId) { it.copy(enabled = enabled) }
    }

    /**
     * Whether notifications are enabled for [websiteId].
     * @see [setEnabled].
     */
    @JvmStatic
    fun isEnabled(context: Context, websiteId: String): Boolean =
        CrispNotificationStore.get(context).record(websiteId).enabled

    /**
     * Passes the new FCM registration token of the device,
     * from `FirebaseMessagingService.onNewToken` of your own service.
     * It is registered on the sessions once their chatbox loads again,
     * notifications sent meanwhile being lost.
     * [CrispNotificationService] calls it.
     *
     * Not called once your app registers with the Firebase installation ID, see [onRegistered].
     */
    @JvmStatic
    fun onNewToken(context: Context, token: String) {
        CrispNotificationStore.get(context).deviceToken = token
    }

    /**
     * Passes the Firebase installation ID (FID) of the device once registered with FCM, from
     * `FirebaseMessagingService.onRegistered` of your own service, if your app registers with it
     * (`firebase_messaging_installation_id_enabled` meta-data).
     * It is registered on the sessions once their chatbox loads again,
     * notifications sent meanwhile being lost.
     * [CrispNotificationService] calls it.
     */
    @JvmStatic
    fun onRegistered(context: Context, installationId: String) {
        CrispNotificationStore.get(context).deviceToken = installationId
    }

    // endregion

    // region Receiving

    /** Whether the FCM message [data][RemoteMessage.getData] is a Crisp notification. */
    @JvmStatic
    fun isCrispNotification(data: Map<String, String>): Boolean = CrispNotificationPayload.isCrisp(data)

    /** Whether the FCM [message][RemoteMessage] is a Crisp notification. */
    @JvmStatic
    fun isCrispNotification(message: RemoteMessage): Boolean = isCrispNotification(message.data)

    /**
     * Shows the Crisp notification of the FCM message [data][RemoteMessage.getData],
     * from `FirebaseMessagingService.onMessageReceived` of your own service.
     * [CrispNotificationService] calls it.
     *
     * When tapped, it opens the chatbox in a modal bottom sheet, above the current screen of your app,
     * or above its launcher `Activity` if your app is not running.
     * With [backStack], it opens it instead (replacing the task of your app, as a `TaskStackBuilder` does),
     * then the chatbox above it, e.g. to open the screen of your app where the chatbox is.
     *
     * Without [opensChatbox], it only opens [backStack] (by default, the launcher `Activity` of your app),
     * whose last `Intent` has the extras of the notification,
     * to show the chatbox component of your choice with [configFrom].
     *
     * Without back stack (an empty [backStack], and no launcher `Activity`), tapping it only dismisses it: the
     * chatbox is never opened alone, which would close the screens of your app.
     *
     * Not shown (returning `false`) if it is not a Crisp notification,
     * if notifications are disabled for its website, not allowed by the user,
     * or turned off for its website (its notification channel, from Android 8),
     * or if its session is not the last one loaded by a chatbox of its website (or was reset since).
     *
     * Once shown, the [listeners][addListener] are notified. When called by [CrispNotificationService],
     * or your own service extending it, its [onNotificationShown][CrispNotificationService.onNotificationShown]
     * is also called, once this returns `true`.
     */
    @JvmStatic
    @JvmOverloads
    fun handleNotification(
        context: Context,
        data: Map<String, String>,
        backStack: TaskStackBuilder? = null,
        opensChatbox: Boolean = true,
    ): Boolean {
        val payload = CrispNotificationPayload.from(data)
        if (payload == null) {
            log(Level.WARN, LOG_TAG, "Notification not shown: it is not a valid Crisp notification")
            return false
        }
        val record = CrispNotificationStore.get(context).record(payload.websiteId)
        log(
            Level.DEBUG,
            LOG_TAG,
            "Notification received for session …${payload.sessionId.takeLast(6)}, " +
                "recorded …${record.session?.sessionId?.takeLast(6)}, revoked ${record.session?.revoked}",
        )
        if (CrispNotificationPolicy.activeSession(record, payload.sessionId) == null) {
            log(Level.WARN, LOG_TAG, "Notification not shown: its session is not the current one of its website")
            return false
        }
        if (!CrispNotificationDisplay.show(context.applicationContext, payload, backStack, opensChatbox)) return false
        log(Level.DEBUG, LOG_TAG, "Notification shown")
        CrispNotificationListeners.instance.notifyShown(data)
        return true
    }

    /** Shows the Crisp notification of the FCM [message][RemoteMessage], see [handleNotification]. */
    @JvmStatic
    @JvmOverloads
    fun handleNotification(
        context: Context,
        message: RemoteMessage,
        backStack: TaskStackBuilder? = null,
        opensChatbox: Boolean = true,
    ): Boolean = handleNotification(context, message.data, backStack, opensChatbox)

    // endregion

    // region Listeners

    /**
     * Registers [listener][CrispNotificationListener] to be notified of the notifications shown,
     * on the main thread.
     * Register it from `Application.onCreate`, not to miss the notifications received in background.
     * In your own service extending [CrispNotificationService], you can also override
     * [onNotificationShown][CrispNotificationService.onNotificationShown], called on its background thread.
     *
     * Does nothing if already registered.
     */
    @JvmStatic
    fun addListener(listener: CrispNotificationListener) {
        CrispNotificationListeners.instance.add(listener)
    }

    /** Unregisters [listener][CrispNotificationListener], which is no longer notified. */
    @JvmStatic
    fun removeListener(listener: CrispNotificationListener) {
        CrispNotificationListeners.instance.remove(listener)
    }

    // endregion

    // region Opening

    /**
     * Whether [intent] was started by tapping a Crisp notification.
     * @see [configFrom].
     */
    @JvmStatic
    fun isCrispIntent(intent: Intent): Boolean = CrispNotificationPayload.fromIntent(intent) != null

    /**
     * The config of the chatbox to show for the Crisp notification tapped that started [intent], or `null` if
     * [intent] was not, or if the chatbox of its session cannot be shown anymore (e.g. it was reset since).
     *
     * Call it from [onCreate][android.app.Activity.onCreate] and [onNewIntent][android.app.Activity.onNewIntent] of the `Activity` opened by the notification, then show
     * the chatbox of your choice with it. Only needed with `opensChatbox = false` (see [handleNotification]), as the
     * notification otherwise opens the chatbox on its own. E.g. with Compose:
     * ```
     * override fun onCreate(savedInstanceState: Bundle?) {
     *     super.onCreate(savedInstanceState)
     *     setContent { App(crispConfig = CrispNotifications.configFrom(this, intent)) }
     * }
     * ```
     * or with a Fragment:
     * ```
     * CrispNotifications.configFrom(this, intent)?.let { config ->
     *     CrispChat.show(this, config.websiteId, config.tokenId)
     * }
     * ```
     *
     * The config is the one the session was loaded with: its `websiteId` and `tokenId`. The session of the
     * notification only chooses whether it is shown, never which session is loaded, so that another session
     * cannot be loaded from an `Intent`.
     */
    @JvmStatic
    fun configFrom(context: Context, intent: Intent): CrispConfig? {
        val (websiteId, sessionId) = CrispNotificationPayload.fromIntent(intent) ?: return null
        val record = CrispNotificationStore.get(context).record(websiteId)
        val session = CrispNotificationPolicy.activeSession(record, sessionId)
        if (session == null) {
            log(Level.WARN, LOG_TAG, "Cannot open the notification: its session is not the current one of its website")
            return null
        }
        return CrispConfig(websiteId = websiteId, tokenId = session.tokenId)
    }

    // endregion
}
