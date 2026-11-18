package im.crisp.sdk.api

import androidx.core.app.TaskStackBuilder

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Shows the Crisp notifications received with Firebase Cloud Messaging, see [CrispNotifications].
 *
 * Declare it in your `AndroidManifest.xml` if Crisp is your only use of FCM, as the SDK does not,
 * as it needs `com.google.firebase:firebase-messaging` in your app:
 * ```xml
 * <service
 *     android:name="im.crisp.sdk.api.CrispNotificationService"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *     </intent-filter>
 * </service>
 * ```
 *
 * Tapping a notification opens the chatbox in a modal bottom sheet, above your launcher `Activity`.
 * Extend it to show the chatbox component of your choice instead (see [CrispNotifications.configFrom]),
 * or another back stack:
 * ```
 * class SupportNotificationService : CrispNotificationService() {
 *     override val opensChatbox = false
 * }
 * ```
 *
 * Otherwise, from your own `FirebaseMessagingService`, call [CrispNotifications.handleNotification],
 * [CrispNotifications.onRegistered] and [CrispNotifications.onNewToken] as this one does.
 */
open class CrispNotificationService : FirebaseMessagingService() {
    /**
     * Whether tapping a notification opens the chatbox in a modal bottom sheet, above your launcher `Activity`
     * (by default), or only your launcher `Activity`, to show the chatbox with [CrispNotifications.configFrom].
     */
    protected open val opensChatbox: Boolean = true

    /** The back stack opened when a notification is tapped, by default your launcher `Activity`. */
    protected open fun backStack(): TaskStackBuilder? = null

    /**
     * Shows the Crisp notifications, see [CrispNotifications.handleNotification].
     * Others are ignored, override it to handle yours, calling `super` for Crisp ones.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        if (CrispNotifications.isCrispNotification(message)) {
            if (CrispNotifications.handleNotification(this, message.data, backStack(), opensChatbox)) {
                onNotificationShown(message.data)
            }
        }
    }

    /**
     * The notification of the FCM message [data] (`RemoteMessage.getData()`) was shown, see
     * [CrispNotifications.handleNotification]. Does nothing by default: override it to react, e.g. to update a badge.
     *
     * Called on the background thread of [onMessageReceived], unlike the [listeners][CrispNotifications.addListener],
     * also notified, on the main thread.
     */
    protected open fun onNotificationShown(data: Map<String, String>) {}

    /**
     * **Deprecated** by `firebase-messaging` 25.1.0, which replaces it by [onRegistered] once your app registers
     * with the Firebase installation ID (`firebase_messaging_installation_id_enabled` meta-data).
     * Still called while it registers with the FCM registration token.
     *
     * Passes the new FCM registration token of the device to Crisp, see [CrispNotifications.onNewToken].
     */
    @Deprecated(
        "Replaced by [onRegistered] in `firebase-messaging`, still called without the Firebase installation ID enabled",
        ReplaceWith("onRegistered(token)")
    )
    override fun onNewToken(token: String) {
        CrispNotifications.onNewToken(this, token)
    }

    /**
     * Passes the Firebase installation ID of the device to Crisp once registered with FCM, see
     * [CrispNotifications.onRegistered].
     *
     * Only called once your app registers with the Firebase installation ID
     * (`firebase_messaging_installation_id_enabled` meta-data), [onNewToken] otherwise.
     */
    override fun onRegistered(installationId: String) {
        CrispNotifications.onRegistered(this, installationId)
    }
}
