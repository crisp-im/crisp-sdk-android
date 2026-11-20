package im.crisp.sdk.internal.notification

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import im.crisp.sdk.internal.chatbox.CrispLegacyStorage
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The [records][CrispNotificationRecord] of the websites and the FID or FCM token of the device.
 *
 * Persisted, shared by all the chatboxes of the app and by the notifications received while none is running.
 * Websites are keyed lowercased, as the pre-3.x SDK did, to migrate its settings.
 *
 * The settings of the pre-3.x SDK are migrated once, before the first read, see [CrispLegacyStorage.notificationRecords].
 *
 * Once the session of a website is no longer active (see [CrispNotificationPolicy.activeSession]),
 * e.g. reset, revoked, replaced or disabled, its notification shown is removed ([onInactive]),
 * not to leave the last message of a user on the device once logged out.
 */
internal class CrispNotificationStore(
    private val preferences: SharedPreferences,
    // The settings of the pre-3.x SDK, read once to migrate them, then removed
    private val legacy: () -> Map<String, CrispNotificationRecord> = { emptyMap() },
    private val removeLegacy: () -> Unit = {},
    // Removes the notification shown for a website, once its session is no longer active
    private val onInactive: (websiteId: String) -> Unit = {},
) {
    // region Records

    @Synchronized
    fun record(websiteId: String): CrispNotificationRecord {
        migrateLegacyOnce()
        val value = preferences.getString(recordKey(websiteId), null) ?: return CrispNotificationRecord()
        return try {
            json.decodeFromString(value)
        } catch (e: SerializationException) {
            log(Level.WARN, LOG_TAG, "Cannot read the notification settings, reset", e)
            CrispNotificationRecord()
        } catch (e: IllegalArgumentException) {
            log(Level.WARN, LOG_TAG, "Cannot read the notification settings, reset", e)
            CrispNotificationRecord()
        }
    }

    /** Saves the record of [websiteId] transformed by [transform], returning it. */
    @Synchronized
    fun update(
        websiteId: String,
        transform: (CrispNotificationRecord) -> CrispNotificationRecord,
    ): CrispNotificationRecord {
        val previous = record(websiteId)
        val record = transform(previous)
        if (record != previous) {
            write(recordKey(websiteId), record)
            val active = previous.session?.let { CrispNotificationPolicy.activeSession(previous, it.sessionId) }
            if (active != null && CrispNotificationPolicy.activeSession(record, active.sessionId) == null) {
                onInactive(websiteId)
            }
        }
        return record
    }

    private fun write(key: String, record: CrispNotificationRecord) {
        preferences.edit {
            if (record == CrispNotificationRecord()) {
                remove(key)
            } else {
                putString(key, json.encodeToString(record))
            }
        }
    }

    // endregion

    // region Device token

    /** The FID or FCM token of the device, last fetched from Firebase or received by `onRegistered` or `onNewToken`. */
    var deviceToken: String?
        @Synchronized get() = preferences.getString(DEVICE_TOKEN_KEY, null)
        @Synchronized set(value) = preferences.edit {
            if (value == null) remove(DEVICE_TOKEN_KEY) else putString(DEVICE_TOKEN_KEY, value)
        }

    // endregion

    // region Migration

    /**
     * Migrates the settings of the pre-3.x SDK, once: those of a website with a record already are kept, e.g. set
     * by [CrispNotifications.setEnabled][im.crisp.sdk.api.notification.CrispNotifications.setEnabled] before.
     */
    private fun migrateLegacyOnce() {
        if (preferences.getBoolean(LEGACY_MIGRATED_KEY, false)) return
        try {
            legacy().forEach { (websiteKey, record) ->
                val key = recordKey(websiteKey)
                if (!preferences.contains(key)) {
                    write(key, record)
                }
            }
            log(Level.INFO, LOG_TAG, "Migrated the notification settings of the pre-3.x SDK, if any")
        } catch (e: Exception) {
            // Not retried, not to fail each time
            log(Level.WARN, LOG_TAG, "Cannot migrate the notification settings of the pre-3.x SDK", e)
        }
        preferences.edit { putBoolean(LEGACY_MIGRATED_KEY, true) }
        removeLegacy()
    }

    /** Migrates the settings of the pre-3.x SDK if not done yet, e.g. before deleting its files. */
    @Synchronized
    fun migrateLegacy() = migrateLegacyOnce()

    // endregion

    companion object {
        private const val LOG_TAG = "CrispNotifications"
        private const val NAME = "im.crisp.sdk.notifications"
        private const val DEVICE_TOKEN_KEY = "device_token"
        private const val LEGACY_MIGRATED_KEY = "legacy_migrated"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private fun recordKey(websiteId: String) = "website.${websiteId.lowercase(Locale.ROOT)}"

        @Volatile
        private var instance: CrispNotificationStore? = null

        fun get(context: Context): CrispNotificationStore = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val legacyStorage = CrispLegacyStorage.from(appContext)
                CrispNotificationStore(
                    appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE),
                    legacy = legacyStorage::notificationRecords,
                    removeLegacy = legacyStorage::removeNotifications,
                    onInactive = { CrispNotificationDisplay.cancel(appContext, it) },
                )
            }.also { instance = it }
        }
    }
}
