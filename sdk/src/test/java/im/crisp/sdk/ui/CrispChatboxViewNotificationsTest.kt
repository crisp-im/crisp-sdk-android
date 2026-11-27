package im.crisp.sdk.ui

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.crisp.sdk.R
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.js.WebViewBridge
import im.crisp.sdk.internal.bridge.js.resolve
import im.crisp.sdk.internal.chatbox.CrispSessions
import im.crisp.sdk.internal.notification.CrispNotificationPolicy
import im.crisp.sdk.internal.notification.CrispNotificationRecord
import im.crisp.sdk.internal.notification.CrispNotificationSession
import im.crisp.sdk.internal.notification.CrispNotificationStore
import im.crisp.sdk.internal.webview.CrispWebView
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The notifications of a [CrispChatboxView] along its page, with its own timeouts,
 * the page being driven as the Web client does, through the bridge (see [CrispWebView.testBridgeListener]).
 */
@RunWith(AndroidJUnit4::class)
class CrispChatboxViewNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    // Process-wide, as in the app, so set up in each test
    private val notifications get() = CrispNotificationStore.get(context)

    // The pages of the WebView created by the view, with their bridge
    private var webView: CrispWebView? = null
    private var bridge: WebViewBridge? = null
    private lateinit var view: CrispChatboxView

    // The session of the website, restored by the Web client, whose token is registered
    private val websiteId = "website"

    @Before fun setUp() {
        CrispWebView.testBridgeListener = { webView, bridge ->
            this.webView = webView
            this.bridge = bridge
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        view = CrispChatboxView(ContextThemeWrapper(activity, R.style.Theme_Crisp_SDK))
        activity.setContentView(view)
        idle()
    }

    @After fun tearDown() {
        CrispWebView.testBridgeListener = null
    }

    // region Page

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    /** Loads [config], creating the WebView once drawn, as the view does. */
    private fun load(config: CrispConfig) {
        view.load(config)
        view.viewTreeObserver.dispatchOnPreDraw()
        idle()
    }

    /** The page loaded last, tagging its events (`{{PAGE_ID}}` in `index.html`). */
    private val pageId: String
        get() {
            val html = shadowOf(webView).lastLoadDataWithBaseURL.data
            return Regex("""page: '([^']+)'""").find(html)!!.groupValues[1]
        }

    /** Sends the event of [channel] with its JSON [body], as the page does. */
    private fun send(channel: String, body: String) {
        bridge!!.onMessage(
            buildJsonObject {
                put("kind", "event")
                put("page", pageId)
                put("channel", channel)
                put("body", body)
            }.toString()
        )
        idle()
    }

    /** The page is ready, then drawn: Robolectric never draws, so once [CrispWebView.runWhenDrawn] stops waiting. */
    private fun ready() {
        send("onInterfaceLifecycle", """{"state":"ready"}""")
        idleFor(Duration.ofSeconds(1))
    }

    private fun joined(sessionId: String) {
        send("onLog", """{"level":"info","ns":"Crisp","value":"Socket state has been changed to connected: true"}""")
        send("onSessionLoaded", """{"session_id":"$sessionId"}""")
    }

    /** The last call made to the page, `null` if the last script evaluated was not a call. */
    private val lastCall: String?
        get() = shadowOf(webView).lastEvaluatedJavascript
            ?.let { Regex("""CrispClient\.\w+\([^)]*\)""").find(it)?.value }

    /** Answers the last call made to the page, once checked it is [call]. */
    private fun answer(call: String) {
        assertEquals(call, lastCall)
        val id = Regex("""id: (\d+)""").find(shadowOf(webView).lastEvaluatedJavascript)!!.groupValues[1].toInt()
        bridge!!.resolve(id, "null")
        idle()
    }

    // endregion

    /**
     * Records the session [sessionId] of the website, loaded without `tokenId` with the token `fcm` registered,
     * then that the website was loaded with a `tokenId`, so that its session must be reset on the next load without
     * `tokenId` (see [CrispSessions]), e.g. retried after failing.
     */
    private fun registered(sessionId: String): CrispNotificationSession {
        val session = CrispNotificationSession(sessionId, tokenId = null, notificationId = "fcm")
        notifications.update(websiteId) { CrispNotificationRecord(enabled = true, session = session) }
        CrispSessions.from(context).onLoad(CrispConfig(websiteId, "user_a"))
        return session
    }

    @Test fun `a reset on load unregisters the token of the session first, then reloads`() {
        registered("session_joined")
        load(CrispConfig(websiteId))
        val firstPage = pageId

        joined("session_joined")
        ready()

        answer("""CrispClient.unregisterNotification("fcm")""")
        assertNull(notifications.record(websiteId).session)
        answer("CrispClient.reset()")
        // A new session, recorded once joined
        assertNotEquals(firstPage, pageId)
        assertFalse(CrispSessions.from(context).onLoad(CrispConfig(websiteId)))
    }

    @Test fun `a reset on load not joined before the loading timeout revokes the session`() {
        val session = registered("session_not_joined")
        load(CrispConfig(websiteId))

        // Restored from the storage, so ready before joined, e.g. offline
        ready()
        // The loading timeout of the view elapses first, as started before
        idleFor(Duration.ofMinutes(1))

        val record = notifications.record(websiteId)
        // Its token kept, to unregister on the next load, which resets it again
        assertEquals(session.copy(revoked = true), record.session)
        assertNull(CrispNotificationPolicy.activeSession(record, session.sessionId))
        assertTrue(lastCall != "CrispClient.reset()")
    }

    @Test fun `a reset on load replaced while unregistering revokes the session`() {
        val session = registered("session_replaced")
        load(CrispConfig(websiteId))
        joined("session_replaced")
        ready()
        assertEquals("""CrispClient.unregisterNotification("fcm")""", lastCall)

        // E.g. loaded again by the app before the page answers
        load(CrispConfig(websiteId))

        assertEquals(session.copy(revoked = true), notifications.record(websiteId).session)
    }
}
