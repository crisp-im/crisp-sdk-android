package im.crisp.sdk.api.notification

import im.crisp.sdk.api.controller.chatbox.CrispEventCallbacks

/**
 * Notified of the Crisp notifications shown, once registered with [CrispNotifications.addListener].
 *
 * Global rather than per chatbox (unlike [CrispEventCallbacks]),
 * as notifications are received while no chatbox is shown, e.g. in background.
 * Register it from `Application.onCreate`,
 * which runs before Firebase Cloud Messaging delivers a notification to a process it started,
 * so that none is missed:
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         CrispNotifications.addListener { data ->
 *             analytics.track("crisp_notification", data["website_id"])
 *         }
 *     }
 * }
 * ```
 *
 * If you declare your own service extending [CrispNotificationService], its
 * [onNotificationShown][CrispNotificationService.onNotificationShown] is also called for each notification shown,
 * on the background thread of the service, e.g. to update a badge.
 *
 * Replaces `EventsCallback.onNotificationReceived` of SDK 2.x, with the same data.
 */
fun interface CrispNotificationListener {
    /**
     * The notification of the FCM message [data][com.google.firebase.messaging.RemoteMessage.getData] was shown to the user,
     * e.g. with its `website_id`, `session_id`, `title` and `body`. Called on the main thread.
     *
     * Not called for the notifications that are not shown,
     * e.g. disabled for their website or not allowed by the user,
     * see [CrispNotifications.handleNotification].
     */
    fun onNotificationReceived(data: Map<String, String>)
}
