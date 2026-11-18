package im.crisp.sdk.api

import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder
import com.google.firebase.messaging.RemoteMessage
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.notification.CrispNotificationDisplay
import im.crisp.sdk.internal.notification.CrispNotificationListeners
import im.crisp.sdk.internal.notification.CrispNotificationPayload
import im.crisp.sdk.internal.notification.CrispNotificationPolicy
import im.crisp.sdk.internal.notification.CrispNotificationStore

/**
 * Notifies the user of the replies of your operators, with Firebase Cloud Messaging (FCM).
 *
 * Notifications are sent to the device of the session a chatbox last loaded for each website.
 * Opted-in per website with [setEnabled], as they need you to:
 * 1. Set up Firebase in your app, adding `com.google.firebase:firebase-messaging` (25.1.2 or later,
 *    BoM 34.18.0 or later), which the SDK does not add for you not to force it on apps without notifications.
 *    The device is registered with its FCM registration token, or with its Firebase installation ID (FID) if your
 *    app enables it (`firebase_messaging_installation_id_enabled` meta-data), whichever FCM allows.
 * 2. Set up your Firebase project in your Crisp Dashboard: `Settings > Chatbox Settings > Push Notifications`.
 * 3. Declare `android.permission.POST_NOTIFICATIONS` as from Android 13,
 *    the chatbox prompts the user to allow notifications once they sent a message.
 * 4. Pass the notifications to the SDK, either:
 *    - declaring [CrispNotificationService] in your `AndroidManifest.xml`, if Crisp is your only use of FCM,
 *    - or, from your own `FirebaseMessagingService`, passing them to [handleNotification] when
 *      [isCrispNotification], and the identifier of the device to [onRegistered] (installation ID) or
 *      [onNewToken] (token).
 * 5. Nothing more: tapping one opens the chatbox in a modal bottom sheet, above your launcher `Activity`.
 *    To show the chatbox component of your choice instead, pass `opensChatbox = false`, see [configFrom].
 *
 * To know when one is shown, e.g. for analytics, register a [CrispNotificationListener] with [addListener].
 *
 * ## Sessions
 *
 * The FCM token of the device is registered on the session of the chatbox once it loads,
 * so notifications are only sent once a chatbox of the website was shown.
 * It is unregistered from it once another session is loaded for the website
 * (e.g. with another `tokenId`, loading the previous session again for a moment first),
 * or once the session is reset.
 * A session [reset][CrispSessionController.Session.reset] while no chatbox is shown (e.g. on logout)
 * stops showing its notifications right away, and is unregistered on the next load of the website.
 *
 * Only the notifications of the last session loaded for a website are shown,
 * not to show those of another user of the device,
 * so the conversations of a user are not shown to another one.
 */
object CrispNotifications {
    private const val LOG_TAG = "CrispNotifications"

    // region Settings

    /**
     * Enables notifications for [websiteId], or disables them. Disabled by default, persisted.
     *
     * Can be called at any time, e.g. once the user opts-in, or from `Application.onCreate`.
     * The token is registered on (or unregistered from) the session of the next chatbox loading [websiteId].
     * Disabling them stops showing them right away.
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

    /** Whether notifications are enabled for [websiteId], see [setEnabled]. */
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
     * When tapped, it opens [backStack] (by default, the launcher `Activity` of your app),
     * then the chatbox above it, in a modal bottom sheet.
     *
     * Without [opensChatbox], it only opens [backStack], whose last `Intent` has the
     * extras of the notification, to show the chatbox component of your choice with [configFrom].
     *
     * Without back stack (an empty [backStack], and no launcher `Activity`), tapping it only dismisses it: the
     * chatbox is never opened alone, which would close the screens of your app.
     *
     * Not shown (returning `false`) if it is not a Crisp notification,
     * if notifications are disabled for its website or not allowed by the user,
     * or if its session is not the last one loaded by a chatbox of its website (or was reset since).
     *
     * Once shown, the [listeners][addListener] are notified.
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
        if (CrispNotificationPolicy.activeSession(record, payload.sessionId) == null) {
            log(Level.WARN, LOG_TAG, "Notification not shown: its session is not the current one of its website")
            return false
        }
        if (!CrispNotificationDisplay.show(context.applicationContext, payload, backStack, opensChatbox)) return false
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

    /** Whether [intent] was started by tapping a Crisp notification, see [configFrom]. */
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
