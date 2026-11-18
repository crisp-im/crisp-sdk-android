package im.crisp.sdk.internal.notification

import im.crisp.sdk.internal.chatbox.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrispNotificationStoreTest {
    private val preferences = FakeSharedPreferences()
    private val session = CrispNotificationSession("session_a", notificationId = "fcm")
    private var legacyReads = 0
    private var legacyRemoved = false
    private var legacy = mapOf("website" to CrispNotificationRecord(enabled = true, session = session))

    private fun store() = CrispNotificationStore(
        preferences,
        legacy = { legacyReads++; legacy },
        removeLegacy = { legacyRemoved = true },
    )

    @Test fun `a website has no record by default`() {
        legacy = emptyMap()

        assertEquals(CrispNotificationRecord(), store().record("website"))
    }

    @Test fun `records are saved, by lowercased website ID as the pre-3x SDK`() {
        legacy = emptyMap()
        store().update("WEBSITE") { it.copy(enabled = true) }

        assertTrue(store().record("website").enabled)
    }

    @Test fun `an empty record is removed`() {
        legacy = emptyMap()
        val store = store()
        store.update("website") { it.copy(enabled = true) }
        store.update("website") { CrispNotificationRecord() }

        assertFalse(preferences.values.keys.any { it.startsWith("website.") })
    }

    @Test fun `the pre-3x settings are migrated once, then removed`() {
        val store = store()

        assertEquals(CrispNotificationRecord(enabled = true, session = session), store.record("website"))
        store.record("website")
        store().record("website")

        assertEquals(1, legacyReads)
        assertTrue(legacyRemoved)
    }

    @Test fun `a record set before migrating is kept`() {
        // E.g. disabled by the app before any read, with a store not migrated yet
        preferences.values["website.website"] = """{"enabled":false}"""

        assertEquals(CrispNotificationRecord(), store().record("website"))
    }

    @Test fun `a failing migration is not retried, nor blocks reading`() {
        val store = CrispNotificationStore(preferences, legacy = { legacyReads++; error("unreadable") })

        assertEquals(CrispNotificationRecord(), store.record("website"))
        store.record("website")

        assertEquals(1, legacyReads)
    }

    @Test fun `an unreadable record is reset`() {
        legacy = emptyMap()
        preferences.values["website.website"] = "{"

        assertEquals(CrispNotificationRecord(), store().record("website"))
    }

    @Test fun `the device token is saved`() {
        val store = store()
        assertNull(store.deviceToken)

        store.deviceToken = "fcm"

        assertEquals("fcm", store().deviceToken)
    }
}
