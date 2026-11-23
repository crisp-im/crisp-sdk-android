package im.crisp.sdk.internal.notification

import android.content.ContextWrapper
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEventBus
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.FakeSharedPreferences
import im.crisp.sdk.internal.permission.CrispPermissionManager
import im.crisp.sdk.internal.bridge.PickerClickEvent
import im.crisp.sdk.internal.bridge.types.PickerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrispNotificationSyncTest {
    private val context = ContextWrapper(null)
    private val chatboxStore = CrispChatboxStore()
    private val store = CrispNotificationStore(FakeSharedPreferences())
    private var canShow = true
    private var fetches = 0
    private var fcmToken: String? = "fcm_1"
    // How long fetching the token takes, as Firebase does not time out (e.g. offline)
    private var fetchDuration = Duration.ZERO
    private val timeout = 15.seconds

    // The notification calls made to the page, with the virtual time they were made at
    private val calls = mutableListOf<Pair<String, Long>>()
    private lateinit var scope: TestScope
    // The session the page answers to getSessionIdentifier()
    private var sessionId = "session_a"
    private val bridge = CrispClientBridge(
        { script ->
            Regex("""CrispClient\.(\w+Notification)\("([^"]*)"""").find(script)?.let {
                calls += "${it.groupValues[1]}:${it.groupValues[2]}" to scope.currentTime
            }
            if ("getSessionIdentifier" in script) "\"$sessionId\"" else "null"
        },
        JSEventBus(),
    )
    // Not the process-wide one, not to space the calls of a test from those of the previous one
    private val spacing = CrispNotificationSpacing()
    private fun sync() = CrispNotificationSync(
        context,
        chatboxStore,
        CrispPermissionManager(context),
        store,
        canShow = { canShow },
        fetchToken = { fetches++; delay(fetchDuration); fcmToken },
        spacing = spacing,
    )
    private val sync = sync()

    private val config = CrispConfig("website", "user_a")
    private val registered = CrispNotificationSession("session_a", "user_a", notificationId = "fcm_1")

    private fun joined(sessionId: String = "session_a") {
        this.sessionId = sessionId
        chatboxStore.onPageFinished()
        chatboxStore.onSocketStateChanged(true)
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        scope = this
        block()
    }

    // region onPageLoaded

    @Test fun `the token is registered once the page is ready while the socket is connected`() = test {
        store.update("website") { it.copy(enabled = true) }
        val job = launch { sync.onPageLoaded(config, bridge, timeout) }

        chatboxStore.onPageFinished()
        runCurrent()
        // Would be buffered while disconnected, then dropped by the servers before joining again
        assertTrue(calls.isEmpty())

        chatboxStore.onSocketStateChanged(true)
        advanceUntilIdle()
        job.join()

        assertEquals(listOf("registerNotification:fcm_1"), calls.map { it.first })
        assertEquals(registered, store.record("website").session)
    }

    @Test fun `nothing is done nor fetched for a website without notifications`() = test {
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertTrue(calls.isEmpty())
        assertEquals(0, fetches)
        assertEquals(CrispNotificationRecord(), store.record("website"))
    }

    @Test fun `a new token replaces the previous one, spaced not to lose an update`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        fcmToken = "fcm_2"
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertEquals(listOf("unregisterNotification:fcm_1", "registerNotification:fcm_2"), calls.map { it.first })
        assertTrue(calls[1].second - calls[0].second >= CrispNotificationSpacing.SAME_SESSION_SPACING.inWholeMilliseconds - 1)
    }

    @Test fun `the token is unregistered once notifications cannot be shown`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        canShow = false
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertEquals(listOf("unregisterNotification:fcm_1"), calls.map { it.first })
        assertEquals(0, fetches)
    }

    @Test fun `a migrated token replaced before the first load is updated once joined`() = test {
        // Migrated from the pre-3.x SDK, then the device token changed (or the app switched to the installation ID)
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered.copy(tokenId = null)) }
        store.deviceToken = "fid_1"
        fcmToken = "fid_1"
        joined()

        sync.onPageLoaded(CrispConfig("website"), bridge, timeout)

        assertEquals(listOf("unregisterNotification:fcm_1", "registerNotification:fid_1"), calls.map { it.first })
        assertEquals("fid_1", store.record("website").session?.notificationId)
    }

    @Test fun `the registered token is kept if the current one cannot be fetched`() = test {
        val record = CrispNotificationRecord(enabled = true, session = registered)
        store.update("website") { record }
        fcmToken = null
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertTrue(calls.isEmpty())
        assertEquals(record, store.record("website"))
    }

    @Test fun `another session joined without token is recorded without it, the previous one being unreachable`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        fcmToken = null
        joined("session_b")

        sync.onPageLoaded(config, bridge, timeout)

        // Cannot be unregistered from session_a while session_b is joined
        assertTrue(calls.isEmpty())
        assertEquals(CrispNotificationSession("session_b", "user_a"), store.record("website").session)
        assertNull(CrispNotificationPolicy.activeSession(store.record("website"), "session_a"))
    }

    @Test fun `the saved token is used if it cannot be fetched`() = test {
        store.update("website") { it.copy(enabled = true) }
        store.deviceToken = "fcm_saved"
        fcmToken = null
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertEquals(listOf("registerNotification:fcm_saved"), calls.map { it.first })
    }

    @Test fun `the saved token is used if it cannot be fetched in time`() = test {
        store.update("website") { it.copy(enabled = true) }
        store.deviceToken = "fcm_saved"
        fetchDuration = Duration.INFINITE
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertEquals(timeout.inWholeMilliseconds, currentTime)
        assertEquals(listOf("registerNotification:fcm_saved"), calls.map { it.first })
    }

    @Test fun `a token received while loaded replaces the registered one right away`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        store.deviceToken = "fcm_1"
        joined()
        val job = launch { sync.followDeviceToken(config, bridge) }
        runCurrent()
        // Already registered
        assertTrue(calls.isEmpty())

        store.deviceToken = "fcm_2"
        advanceUntilIdle()

        assertEquals(listOf("unregisterNotification:fcm_1", "registerNotification:fcm_2"), calls.map { it.first })
        assertEquals("fcm_2", store.record("website").session?.notificationId)
        job.cancel()
    }

    @Test fun `a token received is not registered on a revoked session, nor once notifications cannot be shown`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered.copy(revoked = true)) }
        joined()
        val job = launch { sync.followDeviceToken(config, bridge) }

        store.deviceToken = "fcm_2"
        advanceUntilIdle()
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        canShow = false
        store.deviceToken = "fcm_3"
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
        job.cancel()
    }

    @Test fun `the page is ready even while held not ready, as during a handover`() = test {
        store.update("website") { it.copy(enabled = true) }
        chatboxStore.holdNextReady {}
        joined()

        sync.onPageLoaded(config, bridge, timeout)

        assertEquals(listOf("registerNotification:fcm_1"), calls.map { it.first })
    }

    @Test fun `a new page is not ready until its ready event`() = test {
        store.update("website") { it.copy(enabled = true) }
        joined()
        chatboxStore.onPageStarted()
        chatboxStore.onSocketStateChanged(true)
        val job = launch { sync.onPageLoaded(config, bridge, timeout) }
        runCurrent()

        assertTrue(calls.isEmpty())
        job.cancel()
    }

    // endregion

    // region beforeReset

    @Test fun `the token is unregistered before resetting, without waiting`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        joined()

        sync.beforeReset(config, bridge, 5.seconds)

        assertEquals(listOf("unregisterNotification:fcm_1"), calls.map { it.first })
        assertEquals(0L, currentTime)
        assertNull(store.record("website").session)
    }

    @Test fun `resetting right after registering waits, not to lose the unregistration`() = test {
        store.update("website") { it.copy(enabled = true) }
        joined()

        sync.onPageLoaded(config, bridge, timeout)
        sync.beforeReset(config, bridge, 5.seconds)

        assertEquals(listOf("registerNotification:fcm_1", "unregisterNotification:fcm_1"), calls.map { it.first })
        assertTrue(calls[1].second - calls[0].second >= CrispNotificationSpacing.SAME_SESSION_SPACING.inWholeMilliseconds - 1)
    }

    @Test fun `the updates of a session by two chatboxes at once are spaced`() = test {
        store.update("website") { it.copy(enabled = true) }
        joined()
        // E.g. an embedded chatbox and the sheet of a notification tapped
        val other = sync()

        val first = launch { sync.onPageLoaded(config, bridge, timeout) }
        val second = launch { other.onPageLoaded(config, bridge, timeout) }
        first.join()
        second.join()

        assertEquals(listOf("registerNotification:fcm_1", "registerNotification:fcm_1"), calls.map { it.first })
        assertTrue(calls[1].second - calls[0].second >= CrispNotificationSpacing.SAME_SESSION_SPACING.inWholeMilliseconds - 1)
    }

    @Test fun `the session is reset without unregistering if not joined in time`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }

        sync.beforeReset(config, bridge, 5.seconds)

        assertTrue(calls.isEmpty())
        // Unreachable once reset, replaced by the next session loaded
        assertEquals(registered, store.record("website").session)
    }

    @Test fun `nothing is awaited before resetting a session without token`() = test {
        store.update("website") { it.copy(enabled = true) }

        sync.beforeReset(config, bridge, 5.seconds)

        assertEquals(0L, currentTime)
    }

    // endregion

    // region handover

    @Test fun `the handover loads the recorded tokenId, never its session ID`() {
        val handover = sync.handoverConfig(CrispConfig("website", "user_b"), registered)

        assertEquals("user_a", handover.tokenId)
        assertNull(handover.sessionId)
    }

    @Test fun `the handover restores the recorded session of the pre-3x SDK, not saved by the Web client yet`() {
        val legacy = CrispNotificationSession("session_legacy", notificationId = "fcm_1")
        var restoredFor: CrispConfig? = null

        val handover = sync.handoverConfig(CrispConfig("website", "user_b"), legacy) {
            restoredFor = it
            "session_legacy"
        }

        assertEquals(CrispConfig("website"), restoredFor)
        assertNull(handover.tokenId)
        assertEquals("session_legacy", handover.sessionId)
    }

    @Test fun `the handover never restores a session of the pre-3x SDK other than the recorded one`() {
        val legacy = CrispNotificationSession("session_legacy", notificationId = "fcm_1")

        val handover = sync.handoverConfig(CrispConfig("website", "user_b"), legacy) { "session_other" }

        assertNull(handover.sessionId)
    }

    @Test fun `the handover unregisters the token from the recorded session joined, then forgets it`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        joined()

        val isDone = sync.handOver(CrispConfig("website", "user_b"), registered, bridge, 5.seconds)

        assertTrue(isDone)
        assertEquals(listOf("unregisterNotification:fcm_1"), calls.map { it.first })
        assertNull(store.record("website").session)
        assertNull(sync.handoverFrom(CrispConfig("website", "user_b")))
    }

    @Test fun `a handover joining another session does not unregister there, and forgets it`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        joined("session_other")

        val isDone = sync.handOver(CrispConfig("website", "user_b"), registered, bridge, 5.seconds)

        assertTrue(isDone)
        assertTrue(calls.isEmpty())
        assertNull(store.record("website").session)
    }

    @Test fun `a handover not joined in time forgets the session as unreachable, not to retry on each load`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }

        val isJoined = sync.handOver(CrispConfig("website", "user_b"), registered, bridge, 5.seconds)

        assertFalse(isJoined)
        assertTrue(calls.isEmpty())
        assertNull(store.record("website").session)
        assertTrue(store.record("website").enabled)
        assertNull(sync.handoverFrom(CrispConfig("website", "user_b")))
    }

    @Test fun `an abandoned handover forgets the session as unreachable`() {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }

        sync.dropHandover(CrispConfig("website", "user_b"), registered)

        assertNull(store.record("website").session)
        assertNull(sync.handoverFrom(CrispConfig("website", "user_b")))
    }

    // endregion

    // region Prompt

    @Test fun `a choice in the prompt is handled right away, even while disconnected`() = test {
        store.update("website") { CrispNotificationRecord(enabled = true, session = registered) }
        chatboxStore.onPageFinished()
        val event = PickerClickEvent(CrispNotificationSync.PROMPT_FINGERPRINT, PickerId("notifications"), "skip")

        sync.onPickerClick(config, bridge, event) {}

        assertTrue(store.record("website").session?.promptAnswered == true)
    }

    // endregion
}
