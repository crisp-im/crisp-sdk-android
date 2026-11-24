package im.crisp.sdk.internal.notification

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.appMetaData
import im.crisp.sdk.internal.environment.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging, which the Crisp servers send Android notifications with.
 *
 * `firebase-messaging` is compile-only. The app adds it and sets Firebase up to receive notifications.
 * So its classes may be missing at runtime. They are only referenced here
 * (and by [CrispNotificationService][im.crisp.sdk.api.notification.CrispNotificationService], which the app only declares with it),
 * once [isAvailable] checked them.
 *
 * The device is identified by an FCM registration token, or, if the app enables it,
 * by its Firebase installation ID (FID), see [isInstallationIdEnabled].
 * The Crisp servers accept both, as an opaque identifier, so the SDK calls them both "token".
 */
internal object CrispFirebase {
    private const val LOG_TAG = "CrispNotifications"
    private const val MESSAGING_CLASS = "com.google.firebase.messaging.FirebaseMessaging"

    /**
     * Meta-data of the app enabling the registration with the Firebase installation ID (`firebase-messaging`
     * 25.1.0+), read as `GmsRegistrationClient.isV1RegistrationEnabled` does. Then `getToken` and `onNewToken`
     * are disabled, replaced by `register` and `onRegistered`, and the other way around.
     */
    private const val INSTALLATION_ID_ENABLED = "firebase_messaging_installation_id_enabled"

    private val isUnavailableLogged = AtomicBoolean(false)

    private val hasMessaging: Boolean by lazy {
        try {
            Class.forName(MESSAGING_CLASS)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    /**
     * Whether `firebase-messaging` is added and the default Firebase app initialized, logging once why not.
     * FCM only delivers to the default app, which `FirebaseMessaging.getInstance()` requires.
     */
    fun isAvailable(context: Context): Boolean {
        val reason = when {
            !hasMessaging -> "com.google.firebase:firebase-messaging is not added to the app"
            !isInitialized(context) ->
                "the default Firebase app is not initialized (google-services.json or FirebaseApp.initializeApp)"
            else -> return true
        }
        if (isUnavailableLogged.compareAndSet(false, true)) {
            log(Level.ERROR, LOG_TAG, "Notifications are disabled: $reason")
        }
        return false
    }

    private fun isInitialized(context: Context): Boolean = try {
        // Rather than FirebaseApp.getInstance(), not to throw
        FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
    } catch (e: IllegalStateException) {
        // Deleted meanwhile
        false
    } catch (e: LinkageError) {
        false
    }

    /** Whether the app registers the device with its Firebase installation ID, rather than a token. */
    fun isInstallationIdEnabled(context: Context): Boolean =
        context.appMetaData.getBoolean(INSTALLATION_ID_ENABLED, false)

    /**
     * The identifier of the device to send notifications to, once [available][isAvailable],
     * or `null` (logged) if it cannot be fetched:
     * - its Firebase installation ID once registered with FCM, if [enabled][isInstallationIdEnabled],
     * - otherwise its FCM registration token.
     *
     * Firebase does not time out (e.g. offline), so the caller does.
     *
     * Never throws but to cancel, as it runs in a scope without exception handler, see
     * [CrispNotificationSync][im.crisp.sdk.internal.notification.CrispNotificationSync.onPageLoaded].
     */
    suspend fun fetchToken(context: Context): String? = try {
        val messaging = FirebaseMessaging.getInstance()
        if (isInstallationIdEnabled(context)) {
            // Not sent to before registered with FCM, which the app may not have done yet (e.g. auto-init disabled)
            if (messaging.register().awaitSucceeded("register with FCM")) {
                FirebaseInstallations.getInstance().id.awaitResultOrNull("get the Firebase installation ID")
            } else {
                null
            }
        } else {
            @Suppress("DEPRECATION") // Replaced by register(), disabled without the installation ID enabled
            messaging.token.awaitResultOrNull("get the FCM token")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LinkageError) {
        // firebase-messaging is compile-only, so its version is not enforced:
        // e.g. register() is missing before 25.1.0, while the app enables the installation ID
        log(Level.ERROR, LOG_TAG, "Cannot fetch the FCM token: firebase-messaging 25.1.2 or later is required", e)
        null
    } catch (e: Exception) {
        log(Level.WARN, LOG_TAG, "Cannot fetch the FCM token", e)
        null
    }

    /** The result of this task, or `null` if it fails, logging that it cannot [action]. */
    private suspend fun <T> Task<T>.awaitResultOrNull(action: String): T? =
        if (awaitSucceeded(action)) result else null

    /**
     * Whether this task succeeds, logging that it cannot [action] if not.
     *
     * Firebase fails its tasks rather than throwing (e.g. `IOException` once offline,
     * `IllegalStateException` once the API is disabled), so their exception is only logged here.
     */
    private suspend fun <T> Task<T>.awaitSucceeded(action: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    log(Level.WARN, LOG_TAG, "Cannot $action", task.exception)
                }
                continuation.resume(task.isSuccessful)
            }
        }
}
