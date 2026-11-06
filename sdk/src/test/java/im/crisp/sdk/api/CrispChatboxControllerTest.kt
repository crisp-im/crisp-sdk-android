package im.crisp.sdk.api

import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.bridge.js.JSEventBus
import im.crisp.sdk.internal.chatbox.CrispChatboxState
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.chatbox.CrispControllerDispatcher
import im.crisp.sdk.internal.chatbox.FakeSharedPreferences
import im.crisp.sdk.model.Message
import im.crisp.sdk.model.MessageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class CrispChatboxControllerTest {
    /** A bridge whose JS calls are recorded and answered with [result]. */
    private class FakeBridge(var result: () -> String = { "null" }) {
        val scripts = mutableListOf<String>()
        val events = JSEventBus()
        val bridge = CrispClientBridge({ script -> scripts += script; result() }, events)
    }

    private val fake = FakeBridge()
    private val config = MutableStateFlow<CrispConfig?>(CrispConfig("website"))
    private val state = MutableStateFlow<CrispChatboxState>(CrispChatboxState.Loading)
    private val clientEvents = MutableSharedFlow<CrispClientEvent>(extraBufferCapacity = 64)
    private val preferences = FakeSharedPreferences()
    // Once the main dispatcher is set
    private val dispatcher by lazy { CrispControllerDispatcher(preferences) }

    /** Attaches [controller] to the chatbox, registered to [dispatcher] as `CrispChatboxView` does. */
    private fun attach(controller: CrispChatboxController) {
        dispatcher.attach(CrispControllerDispatcher.Chatbox(fake.bridge, config, state) {
            state.value = CrispChatboxState.Loading
        })
        controller.attach(fake.bridge, config, state, clientEvents, dispatcher)
    }

    /** Records the notified events, by name. */
    private class RecordingCallbacks : CrispEventCallbacks {
        val events = mutableListOf<String>()

        override fun onChatOpened() {
            events += "opened"
        }

        override fun onChatClosed() {
            events += "closed"
        }

        override fun onSessionLoaded(sessionId: String) {
            events += "loaded:$sessionId"
        }

        override fun onMessageSent(message: Message) {
            events += "sent:${message.fingerprint}"
        }

        override fun onMessageReceived(message: Message) {
            events += "received:${message.fingerprint}"
        }
    }

    private fun message(fingerprint: Long) = Message(
        isMe = false,
        content = MessageContent.TextContent("Hello"),
        origin = Message.Origin.NETWORK,
        timestamp = Date(0),
        fingerprint = fingerprint,
        from = Message.Sender.OPERATOR,
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `calls wait until the chatbox is ready`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)

        val job = launch { controller.helpdesk.search() }
        assertTrue(fake.scripts.isEmpty())

        state.value = CrispChatboxState.Ready
        assertEquals(listOf("return await CrispClient.helpdeskSearch();"), fake.scripts)
        assertTrue(job.isCompleted)
    }

    @Test fun `calls made before attaching wait for a chatbox`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        state.value = CrispChatboxState.Ready

        val identifier = async { controller.session.getIdentifier() }
        assertTrue(fake.scripts.isEmpty())

        fake.result = { "\"session_123\"" }
        attach(controller)
        assertEquals("session_123", identifier.await())
    }

    @Test fun `calls wait again while the chatbox reloads`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)
        state.value = CrispChatboxState.Ready
        state.value = CrispChatboxState.Loading

        launch { controller.helpdesk.search() }
        assertTrue(fake.scripts.isEmpty())

        state.value = CrispChatboxState.Ready
        assertEquals(listOf("return await CrispClient.helpdeskSearch();"), fake.scripts)
    }

    @Test fun `failures fall back instead of throwing`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)
        state.value = CrispChatboxState.Ready
        fake.result = { throw JSEvaluationException.JSException("boom") }

        assertNull(controller.user.getEmail())
        assertFalse(controller.session.isOngoing())
    }

    @Test fun `calls on a chatbox not attached to the dispatcher fall back`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        // Unlike attach(), not attached to the dispatcher, so the queued calls could not be made first
        controller.attach(fake.bridge, config, state, clientEvents, dispatcher)
        state.value = CrispChatboxState.Ready

        assertNull(controller.user.getEmail())
        assertTrue(fake.scripts.isEmpty())
    }

    @Test fun `calls made after a failed session reset fall back`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)
        state.value = CrispChatboxState.Ready
        fake.result = { throw JSEvaluationException.JSException("boom") }

        CrispSessionController(dispatcher, CrispConfig("website")).session.reset()

        // Not made on the session which should have been reset, but the reset is retried first
        assertNull(controller.user.getEmail())
        assertEquals(List(2) { "return await CrispClient.reset();" }, fake.scripts)
    }

    @Test fun `callbacks receive the result`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)
        state.value = CrispChatboxState.Ready
        fake.result = { "\"John\"" }

        var nickname: String? = null
        controller.user.getNickname { nickname = it }
        var completed = false
        controller.helpdesk.search { completed = true }

        assertEquals("John", nickname)
        assertTrue(completed)
    }

    @Test fun `message show sends the type and content`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)
        state.value = CrispChatboxState.Ready

        controller.message.show(MessageContent.TextContent("Hello"))

        assertEquals(
            listOf("return await CrispClient.messageShow(\"text\", \"Hello\", null, null);"),
            fake.scripts,
        )
    }

    @Test fun `UI methods can be called without a coroutine`() {
        val controller = CrispChatboxController()
        attach(controller)
        state.value = CrispChatboxState.Ready

        controller.helpdesk.search(completion = null)
        controller.bot.runScenario("welcome", completion = null)

        assertEquals(
            listOf(
                "return await CrispClient.helpdeskSearch();",
                "return await CrispClient.botScenarioRun(\"welcome\");",
            ),
            fake.scripts,
        )
    }

    @Test fun `closing ends pending calls without invoking callbacks`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        attach(controller)

        val nickname = async { controller.user.getNickname() }
        var completed = false
        controller.helpdesk.search { completed = true }
        controller.close()
        state.value = CrispChatboxState.Ready

        assertNull(nickname.await())
        assertFalse(completed)
        assertTrue(fake.scripts.isEmpty())
    }

    @Test fun `closed controllers ignore calls and attachments`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        controller.close()
        attach(controller)
        state.value = CrispChatboxState.Ready

        var nickname: String? = "unset"
        controller.user.getNickname { nickname = it }
        controller.helpdesk.search()

        assertEquals("unset", nickname)
        assertTrue(fake.scripts.isEmpty())
    }

    @Test fun `event callbacks are notified of the attached chatbox events`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val callbacks = RecordingCallbacks()
        controller.addEventCallbacks(callbacks)
        attach(controller)
        fake.result = { "\"session_123\"" }

        controller.notifyChatOpened()
        state.value = CrispChatboxState.Ready
        clientEvents.emit(CrispClientEvent.Loaded)
        clientEvents.emit(CrispClientEvent.MessageSent(message(1)))
        clientEvents.emit(CrispClientEvent.MessageReceived(message(2)))
        controller.notifyChatClosed()

        assertEquals(listOf("opened", "loaded:session_123", "sent:1", "received:2", "closed"), callbacks.events)
    }

    @Test fun `session loaded is skipped when the identifier is unavailable`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val callbacks = RecordingCallbacks()
        controller.addEventCallbacks(callbacks)
        attach(controller)
        state.value = CrispChatboxState.Ready
        fake.result = { throw JSEvaluationException.JSException("boom") }

        clientEvents.emit(CrispClientEvent.Loaded)

        assertTrue(callbacks.events.isEmpty())
    }

    @Test fun `detached chatbox events are not notified`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val callbacks = RecordingCallbacks()
        controller.addEventCallbacks(callbacks)
        attach(controller)
        controller.detach(fake.bridge)

        clientEvents.emit(CrispClientEvent.MessageReceived(message(1)))

        assertTrue(callbacks.events.isEmpty())
    }

    @Test fun `removed or added twice event callbacks are notified accordingly`() {
        val controller = CrispChatboxController()
        val kept = RecordingCallbacks()
        val removed = RecordingCallbacks()
        controller.addEventCallbacks(kept)
        controller.addEventCallbacks(kept)
        controller.addEventCallbacks(removed)
        controller.removeEventCallbacks(removed)

        controller.notifyChatOpened()

        assertEquals(listOf("opened"), kept.events)
        assertTrue(removed.events.isEmpty())
    }

    @Test fun `event callbacks can remove themselves while notified`() {
        val controller = CrispChatboxController()
        val other = RecordingCallbacks()
        controller.addEventCallbacks(object : CrispEventCallbacks {
            override fun onChatOpened() {
                controller.removeEventCallbacks(this)
            }
        })
        controller.addEventCallbacks(other)

        controller.notifyChatOpened()
        controller.notifyChatOpened()

        assertEquals(listOf("opened", "opened"), other.events)
    }

    @Test fun `closed controllers do not notify event callbacks`() = runTest(UnconfinedTestDispatcher()) {
        val controller = CrispChatboxController()
        val callbacks = RecordingCallbacks()
        controller.addEventCallbacks(callbacks)
        attach(controller)
        controller.close()

        controller.addEventCallbacks(callbacks)
        controller.notifyChatOpened()
        clientEvents.emit(CrispClientEvent.MessageReceived(message(1)))

        assertTrue(callbacks.events.isEmpty())
    }
}
