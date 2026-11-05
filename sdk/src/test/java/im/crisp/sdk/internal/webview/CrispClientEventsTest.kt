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
}
