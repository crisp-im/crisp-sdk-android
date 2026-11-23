package im.crisp.sdk.internal.notification

import im.crisp.sdk.api.CrispConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CrispNotificationPolicyTest {
    private val registered = CrispNotificationSession("session_a", tokenId = "user_a", notificationId = "fcm_1")
    private val enabled = CrispNotificationRecord(enabled = true, session = registered)

    // region handoverFrom

    @Test fun `no handover to load the same tokenId, whose session the Web client restores`() {
        assertNull(CrispNotificationPolicy.handoverFrom(enabled, CrispConfig("website", "user_a")))
    }

    @Test fun `a handover to unregister the session of another tokenId`() {
        assertEquals(registered, CrispNotificationPolicy.handoverFrom(enabled, CrispConfig("website", "user_b")))
    }

    @Test fun `a handover to unregister a tokened session before loading without tokenId`() {
        assertEquals(registered, CrispNotificationPolicy.handoverFrom(enabled, CrispConfig("website")))
    }

    @Test fun `no handover if no token is registered`() {
        val record = enabled.copy(session = registered.copy(notificationId = null))

        assertNull(CrispNotificationPolicy.handoverFrom(record, CrispConfig("website", "user_b")))
    }

    @Test fun `a handover even once disabled, to unregister the token left`() {
        val record = enabled.copy(enabled = false)

        assertEquals(registered, CrispNotificationPolicy.handoverFrom(record, CrispConfig("website", "user_b")))
    }

    // endregion

    // region onSessionLoaded

    @Test fun `nothing is recorded for a website that never used notifications`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(CrispNotificationRecord(), "session_a", null, "fcm_1")

        assertEquals(CrispNotificationPlan(CrispNotificationRecord()), plan)
    }

    @Test fun `the token is registered on the session joined, and recorded`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(
            CrispNotificationRecord(enabled = true), "session_a", "user_a", "fcm_1"
        )

        assertEquals("fcm_1", plan.register)
        assertNull(plan.unregister)
        assertEquals(registered, plan.record.session)
    }

    @Test fun `the token is registered again on each load, as servers may have dropped it`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(enabled, "session_a", "user_a", "fcm_1")

        assertEquals("fcm_1", plan.register)
        assertNull(plan.unregister)
    }

    @Test fun `a new token replaces the previous one on the same session`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(enabled, "session_a", "user_a", "fcm_2")

        assertEquals("fcm_1", plan.unregister)
        assertEquals("fcm_2", plan.register)
        assertEquals("fcm_2", plan.record.session?.notificationId)
    }

    @Test fun `the token is unregistered once disabled`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(enabled.copy(enabled = false), "session_a", "user_a", "fcm_1")

        assertEquals("fcm_1", plan.unregister)
        assertNull(plan.register)
        assertNull(plan.record.session?.notificationId)
    }

    @Test fun `the token is unregistered once not allowed anymore`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(enabled, "session_a", "user_a", null)

        assertEquals("fcm_1", plan.unregister)
        assertNull(plan.register)
    }

    @Test fun `a revoked session is neither registered nor unregistered, until reset`() {
        val record = enabled.copy(session = registered.copy(revoked = true))

        val plan = CrispNotificationPolicy.onSessionLoaded(record, "session_a", "user_a", "fcm_1")

        assertNull(plan.register)
        assertNull(plan.unregister)
        assertEquals(record, plan.record)
    }

    @Test fun `another session joined replaces the recorded one, left unreachable`() {
        val plan = CrispNotificationPolicy.onSessionLoaded(enabled, "session_b", "user_a", "fcm_1")

        assertEquals(registered, plan.unreachable)
        // Not unregistered from the session joined, where it is not registered
        assertNull(plan.unregister)
        assertEquals("fcm_1", plan.register)
        assertEquals(CrispNotificationSession("session_b", "user_a", "fcm_1"), plan.record.session)
    }

    @Test fun `the prompt answer is kept on the same session, not on another one`() {
        val record = enabled.copy(session = registered.copy(promptAnswered = true))

        val same = CrispNotificationPolicy.onSessionLoaded(record, "session_a", "user_a", "fcm_1")
        val other = CrispNotificationPolicy.onSessionLoaded(record, "session_b", "user_a", "fcm_1")

        assertEquals(true, same.record.session?.promptAnswered)
        assertEquals(false, other.record.session?.promptAnswered)
    }

    // endregion

    // region onBeforeReset and onHandoverLoaded

    @Test fun `the token of the session reset is unregistered, and the session forgotten`() {
        val (record, token) = CrispNotificationPolicy.onBeforeReset(enabled, "session_a")

        assertEquals("fcm_1", token)
        assertNull(record.session)
        assertEquals(true, record.enabled)
    }

    @Test fun `resetting another session keeps the recorded one`() {
        val (record, token) = CrispNotificationPolicy.onBeforeReset(enabled, "session_b")

        assertNull(token)
        assertSame(enabled, record)
    }

    @Test fun `the handover unregisters the token from the session joined, then forgets it`() {
        val (record, token) = CrispNotificationPolicy.onHandoverLoaded(enabled, registered, "session_a")

        assertEquals("fcm_1", token)
        assertNull(record.session)
    }

    @Test fun `a failed handover does not unregister, but forgets the unreachable session`() {
        val (record, token) = CrispNotificationPolicy.onHandoverLoaded(enabled, registered, sessionId = null)

        assertNull(token)
        assertNull(record.session)
    }

    @Test fun `the handover forgets the session changed meanwhile, not to hand it over again`() {
        val revoked = enabled.copy(session = registered.copy(revoked = true))

        val (record, token) = CrispNotificationPolicy.onHandoverLoaded(revoked, registered, "session_a")

        assertEquals("fcm_1", token)
        assertNull(record.session)
    }

    @Test fun `a handover joining another session does not unregister there, but forgets the unreachable one`() {
        val (record, token) = CrispNotificationPolicy.onHandoverLoaded(enabled, registered, "session_b")

        assertNull(token)
        assertNull(record.session)
    }

    // endregion

    // region revoke and activeSession

    @Test fun `resetting the session of the recorded tokenId revokes it`() {
        val record = CrispNotificationPolicy.revoke(enabled, "user_a")

        assertEquals(true, record.session?.revoked)
        assertNull(CrispNotificationPolicy.activeSession(record, "session_a"))
    }

    @Test fun `resetting the session of another tokenId keeps the recorded one`() {
        assertSame(enabled, CrispNotificationPolicy.revoke(enabled, "user_b"))
        assertSame(enabled, CrispNotificationPolicy.revoke(enabled, null))
    }

    @Test fun `only the recorded session is active`() {
        assertEquals(registered, CrispNotificationPolicy.activeSession(enabled, "session_a"))
        assertNull(CrispNotificationPolicy.activeSession(enabled, "session_b"))
    }

    @Test fun `a disabled or unregistered session is not active`() {
        assertNull(CrispNotificationPolicy.activeSession(enabled.copy(enabled = false), "session_a"))
        assertNull(
            CrispNotificationPolicy.activeSession(
                enabled.copy(session = registered.copy(notificationId = null)), "session_a"
            )
        )
    }

    // endregion
}
