package im.crisp.sdk.internal.webview

import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEventBus
import im.crisp.sdk.internal.chatbox.CrispChatboxState
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.model.MessageContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrispClientEventsTest {
    private val events = JSEventBus()

    private fun message(type: String, content: String, fingerprint: Long) =
        """{"type":"$type","content":$content,"from":"operator","timestamp":1700000000000,"fingerprint":$fingerprint}"""

    @Test fun `messages and readiness are forwarded as client events`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        CrispClientBridge({ "null" }, events).bindTo(store)
        val received = mutableListOf<CrispClientEvent>()
        backgroundScope.launch { store.clientEvents.collect { received += it } }

        events.dispatch("onInterfaceLifecycle", """{"state":"ready"}""")
        events.dispatch("onMessageSent", message("text", "\"Hi\"", 1))
        // Notes cannot be represented by the public model
        events.dispatch("onMessageReceived", message("note", "\"internal\"", 2))
        events.dispatch("onMessageReceived", message("text", "\"Hello\"", 3))

        assertEquals(CrispChatboxState.Ready, store.state.value)
        assertEquals(CrispClientEvent.Loaded, received[0])
        assertEquals(
            listOf(1L to MessageContent.TextContent("Hi"), 3L to MessageContent.TextContent("Hello")),
            received.drop(1).map {
                val message = when (it) {
                    is CrispClientEvent.MessageSent -> it.message
                    is CrispClientEvent.MessageReceived -> it.message
                    CrispClientEvent.Loaded -> error("Unexpected $it")
                }
                message.fingerprint to message.content
            },
        )
        assertEquals(CrispClientEvent.MessageReceived::class, received[2]::class)
    }

    @Test fun `only socket state logs are parsed`() {
        assertEquals(true, parseSocketStateLog("Socket state has been changed to connected: true and failed reconnect: true in context"))
        assertEquals(false, parseSocketStateLog("Socket state has been changed to connected: false and failed reconnect: false in context"))
        assertEquals(null, parseSocketStateLog("Socket state has been changed to connected: undefined"))
        assertEquals(null, parseSocketStateLog("Checking if should auto-kill socket connection, based on user activity"))
    }

    private fun log(value: String) = """{"level":"warn","ns":"[crisp-client.js:1426]","value":"$value"}"""

    @Test fun `the socket state is read from the client logs`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        CrispClientBridge({ "null" }, events).bindTo(store)

        events.dispatch("onLog", log("Socket state has been changed to connected: true and failed reconnect: false in context"))
        assertEquals(true, store.isSocketConnected.value)

        // A session joined is not a socket state
        events.dispatch("onLog", log("Recovered session from network reconnect, not joining it again"))
        assertEquals(true, store.isSocketConnected.value)

        events.dispatch("onLog", log("Socket state has been changed to connected: false and failed reconnect: false in context"))
        assertEquals(false, store.isSocketConnected.value)
    }

    @Test fun `a new page is not connected until it logs it`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        CrispClientBridge({ "null" }, events).bindTo(store)

        events.dispatch("onLog", log("Socket state has been changed to connected: true and failed reconnect: false in context"))
        store.onPageStarted()
        assertEquals(false, store.isSocketConnected.value)

        events.dispatch("onLog", log("Socket state has been changed to connected: true and failed reconnect: false in context"))
        assertEquals(true, store.isSocketConnected.value)
    }
}
