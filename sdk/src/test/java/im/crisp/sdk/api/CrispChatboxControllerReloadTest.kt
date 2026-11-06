package im.crisp.sdk.api

import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.RecordingWebView
import im.crisp.sdk.internal.bridge.js.WebViewBridge
import im.crisp.sdk.internal.bridge.js.resolve
import im.crisp.sdk.internal.chatbox.CrispChatboxState
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.chatbox.CrispControllerDispatcher
import im.crisp.sdk.internal.chatbox.FakeSharedPreferences
import im.crisp.sdk.model.MessageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pending calls when the chatbox reloads: replayed with the same session (website and user),
 * except those made at most once, which fail once dispatched,
 * canceled with another one (e.g. when switching to another website).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrispChatboxControllerReloadTest {
    /** A chatbox, as attached by `CrispChatboxView`, whose page answers or unloads on demand. */
    private inner class FakeChatbox(config: CrispConfig?) {
        val webView = RecordingWebView()
        val webViewBridge = WebViewBridge(webView)
        val bridge = CrispClientBridge(webViewBridge.evaluator, webViewBridge.events)
        val config = MutableStateFlow(config)
        val state = MutableStateFlow<CrispChatboxState>(CrispChatboxState.Loading)
        val clientEvents = MutableSharedFlow<CrispClientEvent>()

        var reloads = 0

        /** Attaches [controller], registered to [dispatcher] as `CrispChatboxView` does. */
        fun attachTo(controller: CrispChatboxController) {
            dispatcher.attach(CrispControllerDispatcher.Chatbox(bridge, config, state) {
                reloads++
                config.value?.let(::load)
            })
            controller.attach(bridge, config, state, clientEvents, dispatcher)
        }

        /** Loads [config] as `CrispChatboxView.load` does: the page is replaced once loading. */
        fun load(config: CrispConfig) {
            this.config.value = config
            state.value = CrispChatboxState.Loading
            webViewBridge.rejectPendingCalls()
        }

        /** Destroys the WebView, as when the view is recreated. */
        fun destroy(controller: CrispChatboxController) {
            controller.detach(bridge)
            dispatcher.detach(bridge)
            webViewBridge.rejectPendingCalls()
        }
    }

    // Shared by the chatboxes, as in the app. Once the main dispatcher is set
    private val dispatcher by lazy { CrispControllerDispatcher(FakeSharedPreferences()) }

    private val websiteA = CrispConfig("website-a")
    private val websiteB = CrispConfig("website-b")
    // Scripts are wrapped by WebViewBridge, so only the calls are counted
    private val FakeChatbox.emailCalls get() = webView.scripts.count { "CrispClient.getUserEmail()" in it }
    private val FakeChatbox.scenarioRuns get() = webView.scripts.count { "CrispClient.botScenarioRun(" in it }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    // region Same session: replayed

    @Test fun `calls interrupted by a reload with the same session are replayed once ready`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            chatbox.load(websiteA)
            assertEquals(1, chatbox.emailCalls)
            assertFalse(result.isCompleted)

            chatbox.state.value = CrispChatboxState.Ready
            assertEquals(2, chatbox.emailCalls)
            chatbox.webViewBridge.resolve(1, "\"john@example.com\"")
            assertEquals("john@example.com", result.await())
        }

    @Test fun `calls interrupted by a new chatbox with the same session are replayed`() =
        runTest(UnconfinedTestDispatcher()) {
            // e.g. a fragment view recreated on rotation, with the controller of a ViewModel
            val controller = CrispChatboxController()
            val previous = FakeChatbox(websiteA).apply { attachTo(controller) }
            previous.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            previous.destroy(controller)
            val next = FakeChatbox(websiteA).apply { attachTo(controller) }
            assertTrue(next.webView.scripts.isEmpty())

            next.state.value = CrispChatboxState.Ready
            assertEquals(1, next.emailCalls)
            next.webViewBridge.resolve(0, "\"john@example.com\"")
            assertEquals("john@example.com", result.await())
        }

    @Test fun `calls are replayed after each reload with the same session`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            repeat(2) {
                chatbox.load(websiteA)
                chatbox.state.value = CrispChatboxState.Ready
            }
            assertEquals(3, chatbox.emailCalls)
            chatbox.webViewBridge.resolve(2, "\"john@example.com\"")
            assertEquals("john@example.com", result.await())
        }

    @Test fun `calls interrupted by another config with the same session are replayed`() =
        runTest(UnconfinedTestDispatcher()) {
            // e.g. the same website and user shown in a sheet
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            chatbox.load(websiteA.copy(presentation = Presentation.sheet))
            assertFalse(result.isCompleted)

            chatbox.state.value = CrispChatboxState.Ready
            assertEquals(2, chatbox.emailCalls)
            chatbox.webViewBridge.resolve(1, "\"john@example.com\"")
            assertEquals("john@example.com", result.await())
        }

    @Test fun `calls waiting when the chatbox reloads with the same session are made once ready`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready
            // Dispatched right away, unanswered: the call below waits for it
            CrispSessionController(dispatcher, websiteA).session.pushEvent("signup")

            val result = async { controller.bot.runScenario("onboarding") }
            chatbox.load(websiteA)
            assertEquals(0, chatbox.scenarioRuns)
            assertFalse(result.isCompleted)

            chatbox.state.value = CrispChatboxState.Ready
            assertEquals(1, chatbox.scenarioRuns)
            // The event, interrupted, is not pushed again
            assertEquals(1, chatbox.webView.scripts.count { "CrispClient.setSessionEvent(" in it })
            chatbox.webViewBridge.resolve(1, "null")
            result.await()
        }

    // endregion

    // region Same session: calls made at most once

    @Test fun `bot scenarios interrupted by a reload with the same session fail, and are not run again`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            var completed = false
            controller.bot.runScenario("onboarding") { completed = true }
            chatbox.load(websiteA)
            assertTrue(completed)

            chatbox.state.value = CrispChatboxState.Ready
            assertEquals(1, chatbox.scenarioRuns)
        }

    @Test fun `messages interrupted by a new chatbox with the same session fail, and are not shown again`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val previous = FakeChatbox(websiteA).apply { attachTo(controller) }
            previous.state.value = CrispChatboxState.Ready

            val result = async { controller.message.show(MessageContent.TextContent("Hello")) }
            assertEquals(1, previous.webView.scripts.count { "CrispClient.messageShow(" in it })
            previous.destroy(controller)
            result.await()

            val next = FakeChatbox(websiteA).apply { attachTo(controller) }
            next.state.value = CrispChatboxState.Ready
            assertTrue(next.webView.scripts.isEmpty())
        }

    // endregion

    // region Another session: canceled

    @Test fun `calls interrupted by loading another website are canceled`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            chatbox.load(websiteB)
            assertNull(result.await())

            chatbox.state.value = CrispChatboxState.Ready
            assertEquals(1, chatbox.emailCalls)
        }

    @Test fun `calls interrupted by loading another token are canceled`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(CrispConfig("website-a", "token-a")).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            chatbox.load(CrispConfig("website-a", "token-b"))

            assertNull(result.await())
        }

    @Test fun `calls interrupted by loading another website and token are canceled`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(CrispConfig("website-a", "token-a")).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            chatbox.load(CrispConfig("website-b", "token-b"))

            assertNull(result.await())
        }

    @Test fun `calls interrupted by loading a blank website are canceled`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            // CrispChatboxView shows an error instead of loading a blank website
            chatbox.load(CrispConfig(""))
            chatbox.state.value = CrispChatboxState.Error(IllegalStateException())

            assertNull(result.await())
        }

    @Test fun `calls waiting for the chatbox are canceled when another session is loaded`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }

            val result = async { controller.user.getEmail() }
            chatbox.load(websiteB)
            assertNull(result.await())

            chatbox.state.value = CrispChatboxState.Ready
            assertTrue(chatbox.webView.scripts.isEmpty())
        }

    @Test fun `calls interrupted by a new chatbox with another session are canceled`() =
        runTest(UnconfinedTestDispatcher()) {
            // e.g. a fragment replaced by one of another website, with the controller of a ViewModel
            val controller = CrispChatboxController()
            val previous = FakeChatbox(websiteA).apply { attachTo(controller) }
            previous.state.value = CrispChatboxState.Ready

            val result = async { controller.user.getEmail() }
            previous.destroy(controller)
            val next = FakeChatbox(websiteB).apply { attachTo(controller) }
            assertNull(result.await())

            next.state.value = CrispChatboxState.Ready
            assertTrue(next.webView.scripts.isEmpty())
        }

    @Test fun `canceled calls still invoke their completion and callback`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready

            var completed = false
            controller.helpdesk.search { completed = true }
            var email: String? = "unset"
            controller.user.getEmail { email = it }
            chatbox.load(websiteB)

            assertTrue(completed)
            assertNull(email)
        }

    // endregion

    // region Calls made around a session change

    @Test fun `calls made after loading another session are made for it`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready
            chatbox.load(websiteB)

            val result = async { controller.user.getEmail() }
            assertTrue(chatbox.webView.scripts.isEmpty())

            chatbox.state.value = CrispChatboxState.Ready
            chatbox.webViewBridge.resolve(0, "\"john@example.com\"")
            assertEquals("john@example.com", result.await())
        }

    @Test fun `calls made before any config is loaded are made for the first one`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()
            val chatbox = FakeChatbox(config = null).apply { attachTo(controller) }

            val result = async { controller.user.getEmail() }
            chatbox.load(websiteA)
            chatbox.state.value = CrispChatboxState.Ready
            chatbox.webViewBridge.resolve(0, "\"john@example.com\"")

            assertEquals("john@example.com", result.await())
        }

    @Test fun `calls made before attaching are made for the first attached config`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = CrispChatboxController()

            val result = async { controller.user.getEmail() }
            val chatbox = FakeChatbox(websiteB).apply { attachTo(controller) }
            chatbox.state.value = CrispChatboxState.Ready
            chatbox.webViewBridge.resolve(0, "\"john@example.com\"")

            assertEquals("john@example.com", result.await())
        }

    // endregion

    // region Session reset through CrispSessionController: reloaded

    @Test fun `session reset reloads the chatbox once done`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
        chatbox.state.value = CrispChatboxState.Ready

        CrispSessionController(dispatcher, websiteA).session.reset()
        assertEquals(0, chatbox.reloads)

        chatbox.webViewBridge.resolve(0, "null")
        assertEquals(1, chatbox.reloads)
        assertEquals(CrispChatboxState.Loading, chatbox.state.value)
    }

    @Test fun `failed session reset does not reload the chatbox`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
        chatbox.state.value = CrispChatboxState.Ready

        CrispSessionController(dispatcher, websiteA).session.reset()
        chatbox.webViewBridge.onMessage("""{"kind":"reject","id":0,"message":"boom"}""")

        assertEquals(0, chatbox.reloads)
        assertEquals(CrispChatboxState.Ready, chatbox.state.value)
    }

    @Test fun `session reset does not reload a chatbox attached meanwhile`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val previous = FakeChatbox(websiteA).apply { attachTo(controller) }
        previous.state.value = CrispChatboxState.Ready

        CrispSessionController(dispatcher, websiteA).session.reset()
        // The page answers while another chatbox is attached, e.g. while the view is recreated
        dispatcher.detach(previous.bridge)
        val next = FakeChatbox(websiteA).apply { attachTo(controller) }
        previous.webViewBridge.resolve(0, "null")

        assertEquals(0, previous.reloads)
        assertEquals(0, next.reloads)
    }

    @Test fun `calls made after a session reset wait for the reload`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val chatbox = FakeChatbox(websiteA).apply { attachTo(controller) }
        chatbox.state.value = CrispChatboxState.Ready
        CrispSessionController(dispatcher, websiteA).session.reset()
        chatbox.webViewBridge.resolve(0, "null")

        val result = async { controller.user.getEmail() }
        assertEquals(0, chatbox.emailCalls)

        chatbox.state.value = CrispChatboxState.Ready
        chatbox.webViewBridge.resolve(1, "\"john@example.com\"")
        assertEquals("john@example.com", result.await())
    }

    // endregion
}
