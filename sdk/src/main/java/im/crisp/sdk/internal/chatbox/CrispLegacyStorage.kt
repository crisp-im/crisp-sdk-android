package im.crisp.sdk.internal.chatbox

import android.content.Context
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
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
 * Its other files, and the sessions that are never restored, are [cleaned up][cleanUp].
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
     * [Cleans up][cleanUp] the files of the legacy pre-3.x SDK, once per process, in the background.
     */
    fun cleanUpOnce() {
        if (isCleanedUp.compareAndSet(false, true)) {
            cleanUpScope.launch { cleanUp() }
        }
    }

    /**
     * Deletes the files of the legacy pre-3.x SDK, except, in its `DiskLruCache` (see [CrispLegacyStorage]):
     * - the sessions that can be restored, not restored yet: those without `tokenId` cached for their website,
     * - the notification entries (`notification_*`) to migrate them later.
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

        private fun ByteArray.indexOf(other: ByteArray): Int =
            (0..size - other.size).firstOrNull { start ->
                other.indices.all { this[start + it] == other[it] }
            } ?: -1
    }
}
