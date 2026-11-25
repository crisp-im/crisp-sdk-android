package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.notification.CrispNotificationRecord
import im.crisp.sdk.internal.notification.CrispNotificationSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.io.Serializable

class CrispLegacyStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val cacheDir: File by lazy { folder.newFolder("cache") }
    private val filesDir: File by lazy { folder.newFolder("files") }
    private val directory: File by lazy { File(cacheDir, "im.crisp.client/cache/data").apply { mkdirs() } }
    private val sessions by lazy { CrispLegacyStorage(cacheDir, filesDir) }

    @Test fun `the session of a website is restored without token ID`() {
        saveSession("website", SESSION_ID)

        assertEquals(SESSION_ID, sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `the session is not restored with a token ID`() {
        saveSession("website", SESSION_ID)

        assertNull(sessions.sessionId(CrispConfig("website", "token")))
    }

    @Test fun `the session is not restored if a token ID was cached for the website`() {
        saveSession("website", SESSION_ID)
        File(directory, "token_website.0").writeBytes(serialize("token"))

        assertNull(sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `a token ID cached for another website does not prevent restoring`() {
        saveSession("website", SESSION_ID)
        File(directory, "token_other.0").writeBytes(serialize("token"))

        assertEquals(SESSION_ID, sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `nothing is restored without session for the website`() {
        saveSession("other", SESSION_ID)

        assertNull(sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `the global session saved before 1_0_15 is never restored`() {
        File(directory, "session.0").writeBytes(serializeSession(SESSION_ID))

        assertNull(sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `the website ID is lowercased, as by the previous SDK`() {
        saveSession("website", SESSION_ID)

        assertEquals(SESSION_ID, sessions.sessionId(CrispConfig("WEBSITE")))
    }

    @Test fun `a website ID that cannot be a cache key is ignored`() {
        File(directory.parentFile, "session_x.0").writeBytes(serializeSession(SESSION_ID))

        assertNull(sessions.sessionId(CrispConfig("../session_x")))
        sessions.remove("../session_x")
        assertTrue(File(directory.parentFile, "session_x.0").exists())
    }

    @Test fun `a removed session is not restored again`() {
        saveSession("website", SESSION_ID)

        sessions.remove("website")

        assertNull(sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `removing a session keeps those of other websites`() {
        saveSession("website", SESSION_ID)
        saveSession("other", OTHER_SESSION_ID)

        sessions.remove("website")

        assertEquals(OTHER_SESSION_ID, sessions.sessionId(CrispConfig("other")))
    }

    @Test fun `a session restored is removed once the cookies are written to disk`() = runTest {
        saveSession("website", SESSION_ID)
        val file = File(directory, "session_website.0")
        var existedWhenFlushed: Boolean? = null
        val sessions = CrispLegacyStorage(cacheDir, filesDir, flushCookies = { existedWhenFlushed = file.exists() })

        sessions.removeSaved("website")

        assertEquals(true, existedWhenFlushed)
        assertFalse(file.exists())
    }

    @Test fun `a session restored is removed even if the cookies cannot be written`() = runTest {
        saveSession("website", SESSION_ID)
        val sessions = CrispLegacyStorage(cacheDir, filesDir, flushCookies = { throw IllegalStateException() })

        sessions.removeSaved("website")

        assertNull(sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `removing a missing session does nothing`() {
        sessions.remove("website")

        assertFalse(File(directory, "session_website.0").exists())
    }

    @Test fun `cleaning up only keeps the sessions that can be restored`() {
        saveSession("website", SESSION_ID)
        saveSession("other", OTHER_SESSION_ID)
        File(directory, "token_website.0").writeBytes(serialize("token"))
        File(directory, "token_gone.0").writeBytes(serialize("token"))
        val unneeded = listOf(
            "journal", "journal.bkp", "website.0", "settings.0", "session.0", "operator.0",
            "settings_website.0", "messages_website.0", "messages_pending_website.0",
            "message_123.0", "upload_456.0", "operator_website.0", "session_website.0.tmp"
        )
        unneeded.forEach { File(directory, it).writeText("x") }
        val notifications = listOf(
            "notification_enabled_website.0", "notification_status_$SESSION_ID.0", "notification_token.0"
        )
        notifications.forEach { File(directory, it).writeText("x") }

        sessions.cleanUp()

        assertEquals(
            // Not the session with a token ID, never restored, nor its token ID, nor the notifications, migrated before
            setOf("session_other.0"),
            directory.list().orEmpty().toSet()
        )
        assertNull(sessions.sessionId(CrispConfig("website")))
        assertEquals(OTHER_SESSION_ID, sessions.sessionId(CrispConfig("other")))
    }

    @Test fun `cleaning up removes the image cache and the attachments`() {
        saveSession("website", SESSION_ID)
        File(cacheDir, "im.crisp.client/cache/img").apply { mkdirs() }.resolve("image").writeText("x")
        File(filesDir, "im.crisp.client/attachments").apply { mkdirs() }.resolve("photo.jpg").writeText("x")
        File(filesDir, "app.txt").writeText("x")
        File(cacheDir, "im.crisp.sdk").apply { mkdirs() }.resolve("upload.jpg").writeText("x")

        sessions.cleanUp()

        assertFalse(File(cacheDir, "im.crisp.client/cache/img").exists())
        assertFalse(File(filesDir, "im.crisp.client").exists())
        // Neither the files of the app, nor those of this SDK
        assertTrue(File(filesDir, "app.txt").exists())
        assertTrue(File(cacheDir, "im.crisp.sdk/upload.jpg").exists())
        assertTrue(File(directory, "session_website.0").exists())
    }

    @Test fun `cleaning up removes all the files of the previous SDK once nothing is kept`() {
        saveSession("website", SESSION_ID)
        File(directory, "token_website.0").writeBytes(serialize("token"))
        File(directory, "journal").writeText("x")

        sessions.remove("website")
        sessions.cleanUp()

        assertFalse(File(cacheDir, "im.crisp.client").exists())
    }

    @Test fun `cleaning up migrates the notifications first, even if it fails`() {
        File(directory, "notification_token.0").writeText("x")
        File(directory, "journal").writeText("x")
        var migrated = emptyList<String>()

        sessions.cleanUp { migrated = directory.list().orEmpty().toList(); error("failed") }

        assertTrue("notification_token.0" in migrated)
        assertFalse(File(cacheDir, "im.crisp.client").exists())
    }

    @Test fun `files that cannot be deleted are kept for the next clean up`() {
        saveSession("website", SESSION_ID)
        val attachments = File(filesDir, "im.crisp.client/attachments").apply { mkdirs() }
        attachments.resolve("photo.jpg").writeText("x")
        File(directory, "website.0").writeText("x")
        // Its files cannot be deleted without write permission on it
        attachments.setWritable(false)
        try {
            sessions.cleanUp()

            // Unless running as root, which can delete them anyway
            assumeTrue(attachments.resolve("photo.jpg").exists())
            // The rest is cleaned up anyway
            assertFalse(File(directory, "website.0").exists())
        } finally {
            attachments.setWritable(true)
        }

        sessions.cleanUp()

        assertFalse(File(filesDir, "im.crisp.client").exists())
        assertTrue(File(directory, "session_website.0").exists())
    }

    @Test fun `cleaning up deletes the preferences of the previous SDK`() {
        val deleted = mutableListOf<String>()

        CrispLegacyStorage(cacheDir, filesDir) { deleted += it; true }.cleanUp()

        assertEquals(listOf("im.crisp.client.internal.cache.Preferences"), deleted)
    }

    @Test fun `cleaning up without files of the previous SDK does nothing`() {
        File(filesDir, "app.txt").writeText("x")

        sessions.cleanUp()

        assertTrue(File(filesDir, "app.txt").exists())
        assertEquals(listOf("app.txt"), filesDir.list().orEmpty().toList())
    }

    @Test fun `an unreadable session is not restored`() {
        File(directory, "session_website.0").writeText("""{"session_id":"$SESSION_ID"}""")

        assertNull(sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `the session ID is parsed from the head of a long session`() {
        // Longer than a block of serialized data (1024 bytes), split by block headers
        val bytes = serializeSession(SESSION_ID, extra = "x".repeat(10_000))

        assertEquals(SESSION_ID, CrispLegacyStorage.parseSessionId(bytes))
    }

    @Test fun `the session ID is the top-level one, not one of its messages`() {
        val bytes = serializeSession(
            SESSION_ID,
            extra = """","sync":{"messages":[{"session_id":"$OTHER_SESSION_ID"}]}"""
        )

        assertEquals(SESSION_ID, CrispLegacyStorage.parseSessionId(bytes))
    }

    @Test fun `a session ID that is not one is not restored`() {
        val bytes = serializeSession("session_x' onload='alert(1)")

        assertNull(CrispLegacyStorage.parseSessionId(bytes))
    }

    @Test fun `a session without session ID is not restored`() {
        assertNull(CrispLegacyStorage.parseSessionId(serialize("""{"session_hash":"hash"}""")))
    }

    @Test fun `data that is not Java serialized is not parsed`() {
        assertNull(CrispLegacyStorage.parseSessionId("""{"session_id":"$SESSION_ID"}""".toByteArray()))
        assertNull(CrispLegacyStorage.parseSessionId(ByteArray(0)))
    }

    // region Notifications

    @Test fun `the notification settings of an enabled website are migrated with its session`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))
        File(directory, "notification_token.0").writeBytes(serialize("fcm"))
        File(directory, "notification_status_$SESSION_ID.0").writeBytes(serialize(PermissionStatus.REPLIED))

        assertEquals(
            mapOf(
                "website" to CrispNotificationRecord(
                    enabled = true,
                    session = CrispNotificationSession(
                        SESSION_ID,
                        notificationId = "fcm",
                        promptAnswered = true,
                        promptShown = true,
                    ),
                )
            ),
            sessions.notificationRecords(),
        )
    }

    @Test fun `the session of a migrated website keeps its tokenId, to load it again`() {
        saveSession("website", SESSION_ID)
        File(directory, "token_website.0").writeBytes(serialize("user"))
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))

        assertEquals("user", sessions.notificationRecords()["website"]?.session?.tokenId)
    }

    @Test fun `the prompt only asked is not answered, but shown again`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))
        File(directory, "notification_status_$SESSION_ID.0").writeBytes(serialize(PermissionStatus.ASKED))

        val session = sessions.notificationRecords()["website"]?.session
        assertEquals(false, session?.promptAnswered)
        assertEquals(true, session?.promptShown)
    }

    @Test fun `the prompt not shown yet is neither answered nor shown again`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))
        File(directory, "notification_status_$SESSION_ID.0").writeBytes(serialize(PermissionStatus.NONE))

        val session = sessions.notificationRecords()["website"]?.session
        assertEquals(false, session?.promptAnswered)
        assertEquals(false, session?.promptShown)
    }

    @Test fun `a website without notifications enabled is not migrated`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(false))
        File(directory, "notification_token.0").writeBytes(serialize("fcm"))

        assertTrue(sessions.notificationRecords().isEmpty())
    }

    @Test fun `an enabled website without session is migrated without session`() {
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))

        assertEquals(mapOf("website" to CrispNotificationRecord(enabled = true)), sessions.notificationRecords())
    }

    @Test fun `strings are read without resolving their class, unlike other objects`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))
        // TC_LONGSTRING, above 65535 bytes, read without class descriptor too
        val longToken = "t".repeat(70_000)
        File(directory, "notification_token.0").writeBytes(serialize(longToken))

        assertEquals(longToken, sessions.notificationRecords()["website"]?.session?.notificationId)
    }

    @Test fun `another serialized class is not deserialized`() {
        File(directory, "notification_enabled_website.0").writeBytes(serialize(java.util.Date()))

        assertTrue(sessions.notificationRecords().isEmpty())
    }

    @Test fun `the notification settings are removed once migrated, keeping the sessions`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))
        File(directory, "notification_token.0").writeBytes(serialize("fcm"))

        sessions.removeNotifications()

        assertFalse(File(directory, "notification_enabled_website.0").exists())
        assertFalse(File(directory, "notification_token.0").exists())
        assertEquals(SESSION_ID, sessions.sessionId(CrispConfig("website")))
    }

    @Test fun `the clean up runs after migrating, which still finds the tokened sessions`() {
        saveSession("website", SESSION_ID)
        File(directory, "token_website.0").writeBytes(serialize("user"))
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))

        var migrated: Map<String, CrispNotificationRecord>? = null
        sessions.cleanUp { migrated = sessions.notificationRecords(); false }

        assertEquals("user", migrated?.get("website")?.session?.tokenId)
        assertFalse(File(directory, "token_website.0").exists())
    }

    @Test fun `a website whose session cannot be read does not prevent migrating the others`() {
        File(directory, "session_broken.0").mkdirs()
        File(directory, "notification_enabled_broken.0").writeBytes(serialize(true))
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))

        val records = sessions.notificationRecords()

        assertEquals(setOf("website"), records.keys)
        assertEquals(SESSION_ID, records["website"]?.session?.sessionId)
    }

    @Test fun `the notification settings left are cleaned up once migrated`() {
        saveSession("website", SESSION_ID)
        File(directory, "notification_enabled_website.0").writeBytes(serialize(true))
        File(directory, "notification_token.0").writeBytes(serialize("fcm"))

        sessions.cleanUp { true }

        assertEquals(setOf("session_website.0"), directory.list().orEmpty().toSet())
    }

    /** As serialized by the pre-3.x SDK: only the constant name is read, not the class. */
    private enum class PermissionStatus { NONE, ASKED, REPLIED }

    // endregion

    private fun saveSession(websiteId: String, sessionId: String) {
        File(directory, "session_$websiteId.0").writeBytes(serializeSession(sessionId))
    }

    /**
     * Mirrors `SessionJoinedEvent` of the previous SDK: serializable fields (written in the class
     * descriptor, with a `w` like its `network` package), but written as JSON with `writeUTF`.
     */
    @Suppress("unused")
    private class SessionJoinedEventNetwork(private val json: String) : Serializable {
        private val sessionID: String? = null
        private val sessionHash: String? = null
        private val lastActive: java.util.Date? = null
        private val buster: Long = 0
        private val segments: List<String>? = null

        private fun writeObject(out: ObjectOutputStream) {
            out.writeUTF(json)
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private fun serializeSession(sessionId: String, extra: String = "") =
        serialize(SessionJoinedEventNetwork("""{"session_id":"$sessionId","session_hash":"hash$extra"}"""))

    private fun serialize(value: Any): ByteArray = ByteArrayOutputStream().also { bytes ->
        ObjectOutputStream(bytes).use { it.writeObject(value) }
    }.toByteArray()

    private companion object {
        const val SESSION_ID = "session_d987a495-75bf-4d25-8aee-b0ad001ca1bd"
        const val OTHER_SESSION_ID = "session_61597b7a-5871-4d68-b5c6-a374d86d6b1b"
    }
}
