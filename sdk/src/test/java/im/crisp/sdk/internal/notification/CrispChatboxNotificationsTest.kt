package im.crisp.sdk.internal.notification

import android.content.ContextWrapper
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.bridge.js.JSEventBus
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispLegacyStorage
import im.crisp.sdk.internal.chatbox.FakeSharedPreferences
import im.crisp.sdk.internal.permission.CrispPermissionManager
import im.crisp.sdk.internal.webview.CrispClientException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CrispChatboxNotificationsTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ContextWrapper(null)
    private val chatboxStore = CrispChatboxStore()
    private val store = CrispNotificationStore(FakeSharedPreferences())
    private var canShow = true
    private var canConnect = true
    private var fetches = 0
    private val timeout = 15.seconds

    // The notification calls made to the page, and the websites whose notification shown was removed
    private val calls = mutableListOf<String>()
    private val dismissed = mutableListOf<String>()
    // Whether the page is replaced, its calls failing
    private var isUnloaded = false
    private val bridge = CrispClientBridge(
        { script ->
            if (isUnloaded) throw JSEvaluationException.PageUnloaded()
            Regex("""CrispClient\.(\w+Notification)\("([^"]*)"""").find(script)?.let {
                calls += "${it.groupValues[1]}:${it.groupValues[2]}"
            }
            "null"
        },
        JSEventBus(),
    )

    private val config = CrispConfig("website", "user_b")
    private val previous = CrispNotificationSession("session_a", "user_a", notificationId = "fcm_1")

    private fun TestScope.notifications() = CrispChatboxNotifications(
        context,
        chatboxStore,
        CrispPermissionManager(context),
        CrispLegacyStorage(folder.newFolder(), folder.newFolder()),
        scope = { backgroundScope },
        timeout = { timeout },
        canConnect = { canConnect },
        createSync = {
            CrispNotificationSync(
                context,
                chatboxStore,
                store,
                canShowNotifications = { canShow },
                fetchToken = { fetches++; "fcm_1" },
                fetchScope = { backgroundScope },
                spacing = CrispNotificationSpacing(),
                cancelShown = { dismissed += it },
            )
        },
        createPrompter = { CrispNotificationPrompter(context, chatboxStore, CrispPermissionManager(context), store) },
    )

    private fun joined(sessionId: String = "session_b") {
        chatboxStore.onSessionLoaded(sessionId)
        chatboxStore.onSocketStateChanged(true)
    }

    /**
     * Runs what is due within a few seconds (e.g. spaced calls), less than [timeout].
     * Not advanceUntilIdle, which ignores the background scope the coroutines are launched in.
     */
    private fun TestScope.settle() {
        advanceTimeBy(5.seconds)
        runCurrent()
    }

    private fun enable(enabled: Boolean = true) {
        store.update("website") { it.copy(enabled = enabled) }
    }

    // region Session

    @Test fun `the token is registered on the session joined`() = runTest {
        enable()
        notifications().start(config, bridge)

        joined()
        settle()

        assertEquals(listOf("registerNotification:fcm_1"), calls)
        assertEquals("fcm_1", store.record("website").session?.notificationId)
    }

    @Test fun `enabling notifications while loaded registers the token, disabling them unregisters it`() = runTest {
        notifications().start(config, bridge)
        joined()
        settle()
        assertTrue(calls.isEmpty())

        enable()
        settle()
        assertEquals(listOf("registerNotification:fcm_1"), calls)

        enable(false)
        settle()
        assertEquals(listOf("registerNotification:fcm_1", "unregisterNotification:fcm_1"), calls)
        assertNull(store.record("website").session?.notificationId)
    }

    @Test fun `the other changes of the record do not sync the session again`() = runTest {
        enable()
        notifications().start(config, bridge)
        joined()
        settle()

        store.update("website") { it.copy(session = it.session?.copy(promptShown = true)) }
        store.update("other") { it.copy(enabled = true) }
        settle()

        assertEquals(listOf("registerNotification:fcm_1"), calls)
        assertEquals(1, fetches)
    }

    @Test fun `notifications allowed while loaded register the token`() = runTest {
        enable()
        canShow = false
        val notifications = notifications()
        notifications.start(config, bridge)
        joined()
        settle()
        assertTrue(calls.isEmpty())

        canShow = true
        notifications.checkAllowed()
        settle()

        assertEquals(listOf("registerNotification:fcm_1"), calls)
    }

    @Test fun `each session joined by the page is synced`() = runTest {
        enable()
        notifications().start(config, bridge)
        joined("session_a")
        settle()

        // E.g. purged by an operator
        chatboxStore.onSessionLoaded("session_b")
        settle()

        assertEquals(listOf("registerNotification:fcm_1", "registerNotification:fcm_1"), calls)
        assertEquals("session_b", store.record("website").session?.sessionId)
    }

    @Test fun `nothing is synced once canceled`() = runTest {
        val notifications = notifications()
        notifications.start(config, bridge)
        joined()
        settle()

        notifications.cancel()
        enable()
        settle()

        assertTrue(calls.isEmpty())
    }

    @Test fun `the notification of the session is removed once the chat shows it`() = runTest {
        enable()
        val notifications = notifications()
        notifications.start(config, bridge)
        joined()
        settle()
        assertTrue(dismissed.isEmpty())

        notifications.onChatOpened(true)
        settle()
        assertEquals(listOf("website"), dismissed)

        // Again once shown again, e.g. back from background
        notifications.onChatOpened(false)
        settle()
        notifications.onChatOpened(true)
        settle()
        assertEquals(listOf("website", "website"), dismissed)
    }

    @Test fun `the notification of another session is not removed`() = runTest {
        // Not recorded: the website does not use notifications
        val notifications = notifications()
        notifications.onChatOpened(true)
        notifications.start(config, bridge)
        joined()
        settle()

        assertTrue(dismissed.isEmpty())
    }

    // endregion

    // region Handover

    private var loaded = mutableListOf<CrispConfig>()
    private var finished = 0
    private val failures = mutableListOf<CrispClientException>()

    private fun CrispChatboxNotifications.handOver(): Boolean = handOverIfNeeded(
        config,
        bridge,
        load = { loaded += it },
        onFinished = { finished++ },
        onFailed = { failures += it },
    )

    @Test fun `the previous session is loaded again to unregister its token there, then forgotten`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }

        assertTrue(notifications().handOver())
        assertEquals(listOf(config.copy(tokenId = "user_a")), loaded)

        joined("session_a")
        settle()

        assertEquals(listOf("unregisterNotification:fcm_1"), calls)
        assertNull(store.record("website").session)
        assertEquals(1, finished)
        assertTrue(failures.isEmpty())
    }

    @Test fun `no handover for the same tokenId`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }

        assertFalse(notifications().handOverIfNeeded(config.copy(tokenId = "user_a"), bridge, { loaded += it }, {}, {}))
        assertTrue(loaded.isEmpty())
    }

    @Test fun `offline, the load fails, keeping the previous session`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }
        canConnect = false

        assertTrue(notifications().handOver())

        assertTrue(loaded.isEmpty())
        assertTrue(failures.single() is CrispClientException.Offline)
        assertEquals(previous, store.record("website").session)
    }

    @Test fun `not joined in time, the load fails, keeping the previous session`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }

        notifications().handOver()
        advanceTimeBy(timeout + 1.seconds)
        runCurrent()

        assertTrue(failures.single() is CrispClientException.LoadingTimeout)
        assertEquals(0, finished)
        assertEquals(previous, store.record("website").session)
    }

    @Test fun `the previous session is kept if the page is replaced meanwhile`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }
        isUnloaded = true

        notifications().handOver()
        joined("session_a")
        settle()

        // Handed over again by the next page
        assertEquals(previous, store.record("website").session)
        assertEquals(0, finished)
        assertTrue(failures.isEmpty())
    }

    @Test fun `the previous session is forgotten if another session is joined`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }

        notifications().handOver()
        // E.g. its storage expired
        joined("session_c")
        settle()

        assertTrue(calls.isEmpty())
        assertNull(store.record("website").session)
        assertEquals(1, finished)
    }

    @Test fun `the previous session is forgotten if its page cannot be loaded`() = runTest {
        store.update("website") { CrispNotificationRecord(enabled = true, session = previous) }

        val isHandingOver = notifications().handOverIfNeeded(
            config,
            bridge,
            load = { throw CrispClientException.WebViewUnavailable() },
            onFinished = { finished++ },
            onFailed = { failures += it },
        )

        assertFalse(isHandingOver)
        assertNull(store.record("website").session)
    }

    // endregion
}
