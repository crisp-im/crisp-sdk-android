package im.crisp.sdk.internal.chatbox

import android.content.Context
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import im.crisp.sdk.internal.notification.CrispNotificationRecord
import im.crisp.sdk.internal.notification.CrispNotificationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sessions saved by the native SDK, from 1.0.15 to 2.x, restored once in the Web client.
 *
 * The native SDK kept them in a `DiskLruCache`, in `im.crisp.client/cache/data` of [cacheDir],
 * one entry per website:
 * - `session_<websiteId>`: the session, a Java serialized `SessionJoinedEvent` written as JSON,
 * - `token_<websiteId>`: the `tokenId` set when it was last used, if any.
 *
 * Before 1.0.15, the session was saved under a single `session` entry, which may not belong to the
 * website of the `settings` entry, so it is never restored.
 *
 * A session is only restored for a [config][CrispConfig] without `tokenId`, and only if no `tokenId`
 * was cached for its website, not to show the session of a user logged in with the native SDK:
 * - with a `tokenId`, the Web client restores the session bound to it on its own,
 *   while a restored session would replace it, then be bound to that `tokenId`,
 * - neither a cached `tokenId` proves that the session is bound to it (it may have been created
 *   before, as the native SDK joined its cached session whatever the `tokenId`), nor its absence
 *   that the session is anonymous (the `tokenId` may have been unset without resetting the session),
 *   but a cached `tokenId` tells that a user was logged in, whose session can be restored with it.
 *   Only a session created anonymously before logging in is then lost, as with the Web client.
 *
 * Entries are read as files, without `DiskLruCache`: each value is in `<key>.0`, only replaced once
 * committed. The native SDK is not used anymore, so its journal does not need to be updated.
 *
 * Its notification settings are [migrated][notificationRecords], then its other files, and the sessions that are
 * never restored, are [cleaned up][cleanUp].
 */
internal class CrispLegacyStorage(private val cacheDir: File, private val filesDir: File) {
    private val dataCacheDirectory = File(cacheDir, DATA_CACHE_DIRECTORY)

    /**
     * The id of the pre-3.x session to restore when loading [config][CrispConfig], if any.
     * Kept until [removed][remove], so that it is restored again if the Web client did not save it.
     */
    fun sessionId(config: CrispConfig): String? {
        if (config.tokenId != null) return null
        val key = key(config.websiteId) ?: return null
        if (File(dataCacheDirectory, "$TOKEN_ENTRY_PREFIX$key$ENTRY_KEY_SUFFIX").exists()) return null
        val file = File(dataCacheDirectory, "$SESSION_ENTRY_PREFIX$key$ENTRY_KEY_SUFFIX")
        if (!file.exists()) return null
        return try {
            parseSessionId(file.readHead())
        } catch (e: Exception) {
            log(Level.WARN, LOG_TAG, "Cannot read the session of the pre-3.x SDK", e)
            null
        }?.also {
            log(Level.INFO, LOG_TAG, "Restoring the session of the pre-3.x SDK")
        }
    }

    /**
     * Removes the pre-3.x session of [websiteId], once restored, or reset, not to restore it again.
     */
    fun remove(websiteId: String) {
        val key = key(websiteId) ?: return
        val file = File(dataCacheDirectory, "$SESSION_ENTRY_PREFIX$key$ENTRY_KEY_SUFFIX")
        if (file.exists() && !file.delete()) {
            log(Level.WARN, LOG_TAG, "Cannot remove the session from the pre-3.x SDK")
        }
    }

    /**
     * [Cleans up][cleanUp] the files of the legacy pre-3.x SDK, once per process, in the background,
     * after [beforeCleanUp], e.g. to migrate its notification settings, which need its sessions and `tokenIds`.
     */
    fun cleanUpOnce(beforeCleanUp: () -> Unit = {}) {
        if (isCleanedUp.compareAndSet(false, true)) {
            cleanUpScope.launch { cleanUp(beforeCleanUp) }
        }
    }

    /** [Cleans up][cleanUp] after [beforeCleanUp], which is best effort, i.e. cleaned up even if it fails. */
    internal fun cleanUp(beforeCleanUp: () -> Unit) {
        try {
            beforeCleanUp()
        } catch (e: Exception) {
            log(Level.WARN, LOG_TAG, "Cannot prepare the clean up of the files of the pre-3.x SDK", e)
        }
        cleanUp()
    }

    /**
     * The notification settings of the pre-3.x SDK (see [CrispNotificationRecord]),
     * by website key (lowercased `websiteId`), restored from its `DiskLruCache` (see [CrispLegacyStorage]),
     * Java serialized:
     * - `notification_enabled_<websiteKey>`: whether `Crisp.enableNotifications` was called for the website,
     * - `notification_token`: the FCM token, registered on the session of each enabled website,
     * - `notification_status_<sessionId>`: whether the user answered the prompt to enable notifications.
     *
     * Only the enabled websites are returned, with their [session][CrispNotificationSession], if any,
     * loaded with the `tokenId` cached for it. Its FCM token is unregistered once the session is replaced,
     * see [CrispNotificationPolicy][im.crisp.sdk.internal.notification.CrispNotificationPolicy].
     *
     * Read before [cleanUp], which deletes the sessions with a `tokenId` and the `tokenIds`.
     */
    fun notificationRecords(): Map<String, CrispNotificationRecord> {
        val files = dataCacheDirectory.listFiles().orEmpty()
        val token = files.firstOrNull { it.name == NOTIFICATION_TOKEN_FILE }?.readSerialized() as? String
        return files.mapNotNull { file ->
            val key = NOTIFICATION_ENABLED_FILE.websiteKey(file) ?: return@mapNotNull null
            if (file.readSerialized() != true) return@mapNotNull null
            val sessionId = File(dataCacheDirectory, "$SESSION_ENTRY_PREFIX$key$ENTRY_KEY_SUFFIX")
                .takeIf { it.exists() }
                ?.let { parseSessionId(it.readHead()) }
            val session = sessionId?.let {
                CrispNotificationSession(
                    sessionId = it,
                    tokenId = File(dataCacheDirectory, "$TOKEN_ENTRY_PREFIX$key$ENTRY_KEY_SUFFIX")
                        .takeIf { file -> file.exists() }
                        ?.readSerialized() as? String,
                    notificationId = token,
                    promptAnswered = File(dataCacheDirectory, "$NOTIFICATION_STATUS_PREFIX$it$ENTRY_KEY_SUFFIX")
                        .takeIf { file -> file.exists() }
                        ?.readBytes()
                        ?.let(::isPromptAnswered) == true
                )
            }
            key to CrispNotificationRecord(enabled = true, session = session)
        }.toMap()
    }

    /** Deletes the notification settings of the pre-3.x SDK, once [migrated][notificationRecords]. */
    fun removeNotifications() {
        dataCacheDirectory.listFiles().orEmpty()
            .filter { NOTIFICATION_FILE.matches(it.name) }
            .forEach { file ->
                if (!file.delete()) {
                    log(Level.WARN, LOG_TAG, "Cannot delete the notification settings of the pre-3.x SDK: ${file.name}")
                }
            }
    }

    /**
     * Reads a Java serialized [String] or [Boolean], the only values of the notification settings,
     * `null` otherwise, see [SerializedValueInputStream].
     */
    private fun File.readSerialized(): Any? = try {
        inputStream().use { SerializedValueInputStream(it).readObject() }
    } catch (e: Exception) {
        log(Level.WARN, LOG_TAG, "Cannot read the notification settings of the pre-3.x SDK: $name", e)
        null
    }

    /**
     * Only resolves [Boolean], rejecting any other class, so that nothing else is deserialized.
     *
     * [String] needs no class to be resolved: it is serialized as a `TC_STRING` (or `TC_LONGSTRING`) record,
     * its text without class descriptor, which `readObject` reads without calling [resolveClass].
     * That is only called for the records with a class descriptor
     * (`TC_CLASSDESC`: objects, enums, arrays and classes), e.g. `Boolean`.
     */
    private class SerializedValueInputStream(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> =
            if (desc.name == Boolean::class.javaObjectType.name) {
                super.resolveClass(desc)
            } else {
                throw InvalidClassException(desc.name, "Unexpected class")
            }
    }

    /**
     * Deletes the files of the legacy pre-3.x SDK, in its `DiskLruCache` (see [CrispLegacyStorage]), except:
     * - the sessions that can be restored, not restored yet: those without `tokenId` cached for their website,
     * - the notification entries (`notification_*`), deleted once [migrated][notificationRecords] instead.
     *
     * So deletes:
     * - the other entries (e.g. `website`, `settings_*`, `messages_*`, `message_*`, `upload_*`, or `session`
     *   and `settings` before 1.0.15), including the sessions with a `tokenId`, never restored, then their
     *   `tokenId`, and the journal, then the whole `im.crisp.client` directory of [cacheDir] once nothing is kept,
     * - its image cache (before 2.0), in `im.crisp.client/cache/img` of [cacheDir],
     * - the photos and videos taken to be sent (2.x), in `im.crisp.client/attachments` of [filesDir].
     *
     * Never deletes a session that can be restored, nor a `tokenId` before its session (if it cannot be
     * deleted, its `tokenId` is kept, so that it is still not restored), so it can run while one is being
     * read or removed.
     *
     * Best effort: what cannot be deleted is left, so deleting it is retried on the next launch.
     */
    internal fun cleanUp() {
        // Found to be deleted, logged once cleaned up, and those that could not be,
        // logged to be retried
        var isFound = false
        val notDeleted = mutableListOf<File>()
        fun delete(file: File) {
            if (!file.exists()) return
            isFound = true
            if (!file.deleteIfExists()) notDeleted += file
        }

        try {
            delete(File(filesDir, CLIENT_DIRECTORY))
            delete(File(cacheDir, IMAGE_CACHE_DIRECTORY))
            val files = dataCacheDirectory.listFiles().orEmpty()
            val (tokens, others) = files.partition { TOKEN_FILE.matches(it.name) }
            val tokenKeys = tokens.mapNotNull { TOKEN_FILE.websiteKey(it) }.toSet()
            others.filter { file ->
                // Kept: the sessions that can be restored (without a paired token), and notifications
                SESSION_FILE.websiteKey(file).let { it == null || it in tokenKeys } &&
                    !NOTIFICATION_FILE.matches(file.name)
            }.forEach(::delete)
            // Only once their session is deleted, not to restore it meanwhile, or if it cannot be
            tokens.filter { token ->
                val session = "$SESSION_ENTRY_PREFIX${TOKEN_FILE.websiteKey(token)}$ENTRY_KEY_SUFFIX"
                !File(dataCacheDirectory, session).exists()
            }.forEach(::delete)
            if (dataCacheDirectory.list().isNullOrEmpty()) {
                delete(File(cacheDir, CLIENT_DIRECTORY))
            }
        } catch (e: Exception) {
            log(Level.WARN, LOG_TAG, "Cannot clean up the files of the pre-3.x SDK", e)
            return
        }

        if (notDeleted.isNotEmpty()) {
            log(
                Level.WARN,
                LOG_TAG,
                "Cannot delete files of the pre-3.x SDK, retried on next launch: " +
                    notDeleted.joinToString { it.name }
            )
        } else if (isFound) {
            log(Level.INFO, LOG_TAG, "Cleaned up files of the pre-3.x SDK")
        }
    }

    /**
     * Deletes this file or this directory with its content, returning whether it does not exist anymore:
     * `true` if it did not exist or is deleted, `false` if (some of) it could not be, the rest being deleted.
     */
    private fun File.deleteIfExists(): Boolean = !exists() || deleteRecursively()

    private fun Regex.websiteKey(file: File): String? = matchEntire(file.name)?.groupValues?.get(1)

    // The sessionId is near the start of the file, after the class descriptor, so no need to read it all
    private fun File.readHead(): ByteArray = inputStream().use { input ->
        val buffer = ByteArray(minOf(length(), HEAD_SIZE.toLong()).toInt())
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        buffer.copyOf(read)
    }

    companion object {
        private const val LOG_TAG = "CrispLegacyStorage"
        // Pre 3.x DataCache in cacheDir
        private const val DATA_CACHE_DIRECTORY = "im.crisp.client/cache/data"
        // Pre 2.x Attachments cache in cacheDir
        private const val IMAGE_CACHE_DIRECTORY = "im.crisp.client/cache/img"
        // Root of all 2.x Files in cacheDir and filesDir
        private const val CLIENT_DIRECTORY = "im.crisp.client"
        private const val SESSION_ENTRY_PREFIX = "session_"
        private const val TOKEN_ENTRY_PREFIX = "token_"
        private const val ENTRY_KEY_SUFFIX = ".0"
        private const val HEAD_SIZE = 16 * 1024

        // DiskLruCache 2.0.2 keys are [a-z0-9_-]{1,64}, so other website IDs had no session saved.
        // Also prevents reading files outside of the directory.
        private val KEY = Regex("^[a-z0-9_-]{1,${64 - SESSION_ENTRY_PREFIX.length}}$")

        // Java serialization stream header (magic number and version)
        private val STREAM_HEADER = byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x05)

        // SessionJoinedEvent.writeObject writes it as JSON (Gson) with writeUTF, `session_id` first.
        // Neither the header nor the class descriptor contains it, which only has Java field names.
        private val SESSION_ID_FIELD = "\"session_id\":\"".toByteArray(Charsets.US_ASCII)

        // The entries kept until restored, by the key of their website
        private val SESSION_FILE = Regex("^$SESSION_ENTRY_PREFIX([a-z0-9_-]+)\\$ENTRY_KEY_SUFFIX$")
        private val TOKEN_FILE = Regex("^$TOKEN_ENTRY_PREFIX([a-z0-9_-]+)\\$ENTRY_KEY_SUFFIX$")
        // notification_enabled_<websiteId>, notification_status_<sessionId> and notification_token (2.x)
        private val NOTIFICATION_FILE = Regex("^notification_[a-z0-9_-]+\\$ENTRY_KEY_SUFFIX$")
        private val NOTIFICATION_ENABLED_FILE = Regex("^notification_enabled_([a-z0-9_-]+)\\$ENTRY_KEY_SUFFIX$")
        private const val NOTIFICATION_STATUS_PREFIX = "notification_status_"
        private const val NOTIFICATION_TOKEN_FILE = "notification_token$ENTRY_KEY_SUFFIX"

        // CrispNotificationClient.PermissionStatus, Java serialized as an enum: its constant name,
        // after the class descriptor.
        // REPLIED once the user answered the prompt, ASKED while shown, NONE otherwise
        private val PROMPT_REPLIED = "REPLIED".toByteArray(Charsets.US_ASCII)

        private val isCleanedUp = AtomicBoolean(false)
        private val cleanUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Injected in the page, so only accept what a session ID is made of
        private val SESSION_ID = Regex("^session_[0-9A-Za-z-]{1,64}$")

        fun from(context: Context) = CrispLegacyStorage(context.cacheDir, context.filesDir)

        private fun key(websiteId: String): String? =
            websiteId.lowercase(Locale.ROOT).takeIf(KEY::matches)

        /** The `sessionId` in [bytes], the head of a serialized `SessionJoinedEvent`, if any. */
        internal fun parseSessionId(bytes: ByteArray): String? {
            if (bytes.size < STREAM_HEADER.size ||
                !STREAM_HEADER.indices.all { bytes[it] == STREAM_HEADER[it] }
            ) {
                return null
            }
            val start = bytes.indexOf(SESSION_ID_FIELD).takeIf { it >= 0 }
                ?.plus(SESSION_ID_FIELD.size) ?: return null
            val end = (start until bytes.size).firstOrNull { bytes[it] == '"'.code.toByte() }
                ?: return null
            return String(bytes, start, end - start, Charsets.US_ASCII).takeIf(SESSION_ID::matches)
        }

        /** Whether [bytes], a serialized `PermissionStatus`, tells that the user answered the prompt. */
        internal fun isPromptAnswered(bytes: ByteArray): Boolean =
            bytes.size >= STREAM_HEADER.size &&
                STREAM_HEADER.indices.all { bytes[it] == STREAM_HEADER[it] } &&
                bytes.indexOf(PROMPT_REPLIED) >= 0

        private fun ByteArray.indexOf(other: ByteArray): Int =
            (0..size - other.size).firstOrNull { start ->
                other.indices.all { this[start + it] == other[it] }
            } ?: -1
    }
}
