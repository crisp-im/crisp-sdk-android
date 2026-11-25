package im.crisp.catalog.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import im.crisp.catalog.settings.ChatboxPreferences
import im.crisp.sdk.api.notification.CrispNotifications
import im.crisp.sdk.api.controller.CrispSessionController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only: drives the catalog from `adb`, for the automated notification tests.
 *
 * ```
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.SET_TOKEN_ID --es token_id user_a
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.SET_TOKEN_ID
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.SET_WEBSITE_ID --es website_id <id>
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.RESET_SESSION
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.SET_NOTIFICATIONS_ENABLED --ez enabled false
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.SIMULATE_NOTIFICATION --es session_id <id>
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.ADD_LISTENER
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.SET_EMAIL --es email <email>
 * adb shell am broadcast -n im.crisp.catalog/.debug.DebugCommandReceiver -a im.crisp.catalog.debug.DELETE_FCM_TOKEN
 * ```
 *
 * `SIMULATE_NOTIFICATION` passes a Crisp payload to [CrispNotifications.handleNotification], as a push would,
 * without FCM.
 *
 * Each command logs `Catalog/Debug` once done. [CrispSessionController] and [CrispNotifications] calls apply to the
 * config of the chatboxes ([ChatboxPreferences]), and chatboxes reload when it changes.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_TOKEN_ID -> ChatboxPreferences.setTokenId(context, intent.getStringExtra(EXTRA_TOKEN_ID))
            ACTION_SET_WEBSITE_ID -> intent.getStringExtra(EXTRA_WEBSITE_ID)?.let {
                ChatboxPreferences.setWebsiteId(context, it)
            }

            ACTION_RESET_SESSION -> CrispSessionController(context, ChatboxPreferences.getConfig(context)).session.reset()
            ACTION_SET_EMAIL -> intent.getStringExtra(EXTRA_EMAIL)?.let {
                CrispSessionController(context, ChatboxPreferences.getConfig(context)).user.setEmail(it)
            }

            ACTION_SET_NOTIFICATIONS_ENABLED -> CrispNotifications.setEnabled(
                context,
                ChatboxPreferences.getWebsiteId(context),
                intent.getBooleanExtra(EXTRA_ENABLED, true),
            )

            // As if received from FCM, see CrispNotificationService
            ACTION_SIMULATE_NOTIFICATION -> {
                val data = mapOf(
                    "sender" to "crisp",
                    "website_id" to (intent.getStringExtra(EXTRA_WEBSITE_ID) ?: ChatboxPreferences.getWebsiteId(context)),
                    "session_id" to intent.getStringExtra(EXTRA_SESSION_ID).orEmpty(),
                    "title" to (intent.getStringExtra(EXTRA_TITLE) ?: "Operator"),
                    "body" to (intent.getStringExtra(EXTRA_BODY) ?: "Simulated reply"),
                )
                val shown = CrispNotifications.handleNotification(context, data)
                Log.i(TAG, "Simulated notification shown: $shown")
            }

            // The next chatbox load fetches a new one, replacing it on the session.
            // By reflection, so that this receiver also builds without Firebase
            ACTION_DELETE_FCM_TOKEN -> try {
                val messaging = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
                val instance = messaging.getMethod("getInstance").invoke(null)
                messaging.getMethod("deleteToken").invoke(instance)
                Log.i(TAG, "FCM token deletion requested")
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "Cannot delete the FCM token: Firebase is not set up", e)
            }

            ACTION_ADD_LISTENER -> if (listenerAdded.compareAndSet(false, true)) {
                CrispNotifications.addListener { data -> Log.i(TAG, "Listener received: ${data.keys.sorted()}") }
            }

            else -> {
                Log.w(TAG, "Unknown command ${intent.action}")
                return
            }
        }
        Log.i(TAG, "Done ${intent.action} for ${ChatboxPreferences.getConfig(context)}")
    }

    private companion object {
        private const val TAG = "Catalog/Debug"
        private const val PREFIX = "im.crisp.catalog.debug."
        private const val ACTION_SET_TOKEN_ID = "${PREFIX}SET_TOKEN_ID"
        private const val ACTION_SET_WEBSITE_ID = "${PREFIX}SET_WEBSITE_ID"
        private const val ACTION_RESET_SESSION = "${PREFIX}RESET_SESSION"
        private const val ACTION_SET_NOTIFICATIONS_ENABLED = "${PREFIX}SET_NOTIFICATIONS_ENABLED"
        private const val ACTION_SIMULATE_NOTIFICATION = "${PREFIX}SIMULATE_NOTIFICATION"
        private const val ACTION_ADD_LISTENER = "${PREFIX}ADD_LISTENER"
        private const val ACTION_SET_EMAIL = "${PREFIX}SET_EMAIL"
        private const val ACTION_DELETE_FCM_TOKEN = "${PREFIX}DELETE_FCM_TOKEN"
        private const val EXTRA_EMAIL = "email"
        private const val EXTRA_TOKEN_ID = "token_id"
        private const val EXTRA_WEBSITE_ID = "website_id"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"

        // Added once per process
        private val listenerAdded = AtomicBoolean(false)
    }
}
