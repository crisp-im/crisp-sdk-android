package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.api.CrispConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrispSessionsTest {
    private val preferences = FakeSharedPreferences()
    private val sessions = CrispSessions(preferences)

    @Test fun `a website loaded without ever having a token ID is not reset`() {
        assertFalse(sessions.onLoad(CrispConfig("website")))
    }

    @Test fun `a website loaded with a token ID is not reset`() {
        assertFalse(sessions.onLoad(CrispConfig("website", "token")))
        assertFalse(sessions.onLoad(CrispConfig("website", "token")))
    }

    @Test fun `a website loaded without its previous token ID is reset`() {
        sessions.onLoad(CrispConfig("website", "token"))

        assertTrue(sessions.onLoad(CrispConfig("website")))
    }

    @Test fun `a website is reset until its session is`() {
        sessions.onLoad(CrispConfig("website", "token"))

        // e.g. the reset failed, retried on the next load
        assertTrue(sessions.onLoad(CrispConfig("website")))
        assertTrue(sessions.onLoad(CrispConfig("website")))

        sessions.onSessionReset("website")
        assertFalse(sessions.onLoad(CrispConfig("website")))
    }

    @Test fun `websites are reset separately`() {
        sessions.onLoad(CrispConfig("website-a", "token"))

        assertFalse(sessions.onLoad(CrispConfig("website-b")))
        assertTrue(sessions.onLoad(CrispConfig("website-a")))
    }

    @Test fun `token IDs are remembered across views and launches`() {
        sessions.onLoad(CrispConfig("website", "token"))

        // Another chatbox (e.g. a replacing fragment), or the app launched again, reading the same preferences
        assertTrue(CrispSessions(preferences).onLoad(CrispConfig("website")))
    }

    @Test fun `loading with a token ID again after a reset is remembered`() {
        sessions.onLoad(CrispConfig("website", "token-a"))
        sessions.onSessionReset("website")
        sessions.onLoad(CrispConfig("website", "token-b"))

        assertTrue(sessions.onLoad(CrispConfig("website")))
    }
}
