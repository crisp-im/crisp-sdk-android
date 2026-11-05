package im.crisp.sdk.internal.chatbox

import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.js.JSEvaluationException
import im.crisp.sdk.internal.bridge.js.JSEventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrispControllerDispatcherTest {
    /** A chatbox whose JS calls are recorded and answered with [result]. */
    private class FakeChatbox(config: CrispConfig) {
        val scripts = mutableListOf<String>()
        var result: suspend (String) -> String = { "null" }
        val bridge = CrispClientBridge({ script -> scripts += script; result(script) }, JSEventBus())
        val config = MutableStateFlow<CrispConfig?>(config)
        val state = MutableStateFlow<CrispChatboxState>(CrispChatboxState.Loading)
        var reloads = 0
        val chatbox = CrispControllerDispatcher.Chatbox(bridge, this.config, state) { reloads++ }

        /** The called Web client functions, e.g. `setUserEmail`. */
        val functions get() = scripts.map { it.substringAfter("CrispClient.").substringBefore("(") }
    }

    private val preferences = FakeSharedPreferences()
    // Once the main dispatcher is set
    private val dispatcher by lazy { CrispControllerDispatcher(preferences) }
    private val config = CrispConfig("website", "token")
    private val key = config.session
    private val other = CrispConfig("website", "other")

    private fun event(name: String) = CrispSessionCall.PushEvent(name, null, null)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `calls are saved until dispatched, in order`() = runTest(UnconfinedTestDispatcher()) {
        dispatcher.add(key, CrispSessionCall.SetEmail("john@example.com", null))
        dispatcher.add(key, event("1"))
        dispatcher.add(key, CrispSessionCall.SetSegments(listOf("vip"), overwrite = false))

        // e.g. the app is killed, then launched again
        val chatbox = FakeChatbox(config)
        val result = CrispControllerDispatcher(preferences).dispatch(config, chatbox.bridge)

        assertEquals(CrispControllerDispatcher.Result.DISPATCHED, result)
        assertEquals(listOf("setUserEmail", "setSessionEvent", "setSessionSegments"), chatbox.functions)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun `calls are dispatched right away to a chatbox ready with the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            dispatcher.attach(chatbox.chatbox)
            chatbox.state.value = CrispChatboxState.Ready

            dispatcher.add(key, CrispSessionCall.SetNickname("John"))

            assertEquals(listOf("return await CrispClient.setUserNickname(\"John\");"), chatbox.scripts)
            assertTrue(preferences.values.isEmpty())
        }

    @Test fun `calls wait for a chatbox ready with the session`() = runTest(UnconfinedTestDispatcher()) {
        val loading = FakeChatbox(config)
        val otherToken = FakeChatbox(CrispConfig("website", "other")).apply { state.value = CrispChatboxState.Ready }
        val anonymous = FakeChatbox(CrispConfig("website")).apply { state.value = CrispChatboxState.Ready }
        listOf(loading, otherToken, anonymous).forEach { dispatcher.attach(it.chatbox) }

        dispatcher.add(key, CrispSessionCall.SetNickname("John"))

        assertTrue(loading.scripts.isEmpty() && otherToken.scripts.isEmpty() && anonymous.scripts.isEmpty())
        dispatcher.dispatch(config, loading.bridge)
        assertEquals(listOf("setUserNickname"), loading.functions)
    }

    @Test fun `calls are not dispatched to a detached chatbox`() = runTest(UnconfinedTestDispatcher()) {
        val chatbox = FakeChatbox(config).apply { state.value = CrispChatboxState.Ready }
        dispatcher.attach(chatbox.chatbox)
        dispatcher.detach(chatbox.bridge)

        dispatcher.add(key, CrispSessionCall.SetNickname("John"))

        assertTrue(chatbox.scripts.isEmpty())
    }

    @Test fun `failing calls are dropped`() = runTest(UnconfinedTestDispatcher()) {
        val chatbox = FakeChatbox(config)
        chatbox.result = { script ->
            if ("setUserEmail" in script) throw JSEvaluationException.JSException("invalid email") else "null"
        }
        dispatcher.add(key, CrispSessionCall.SetEmail("invalid", null))
        dispatcher.add(key, CrispSessionCall.SetNickname("John"))

        assertEquals(CrispControllerDispatcher.Result.DISPATCHED, dispatcher.dispatch(config, chatbox.bridge))
        assertEquals(listOf("setUserEmail", "setUserNickname"), chatbox.functions)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun `calls left when the page is unloaded are dispatched once reloaded, not an interrupted event`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            dispatcher.add(key, event("1"))
            dispatcher.add(key, event("2"))
            dispatcher.add(key, event("3"))
            chatbox.result = { script ->
                if (chatbox.scripts.size == 2) throw JSEvaluationException.PageUnloaded() else "null"
            }

            assertEquals(CrispControllerDispatcher.Result.PAGE_UNLOADED, dispatcher.dispatch(config, chatbox.bridge))

            chatbox.scripts.clear()
            chatbox.result = { "null" }
            assertEquals(CrispControllerDispatcher.Result.DISPATCHED, dispatcher.dispatch(config, chatbox.bridge))
            assertEquals(listOf("return await CrispClient.setSessionEvent(\"3\", null, null);"), chatbox.scripts)
        }

    @Test fun `a reset drops the calls made before, and keeps those made after for the new session`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            dispatcher.add(key, CrispSessionCall.SetNickname("John"))
            dispatcher.reset(key)
            dispatcher.add(key, CrispSessionCall.SetNickname("Jane"))

            assertEquals(CrispControllerDispatcher.Result.RESET_PENDING, dispatcher.dispatch(config, chatbox.bridge))
            assertTrue(chatbox.scripts.isEmpty())

            dispatcher.onSessionReset(config)
            assertEquals(CrispControllerDispatcher.Result.DISPATCHED, dispatcher.dispatch(config, chatbox.bridge))
            assertEquals(listOf("return await CrispClient.setUserNickname(\"Jane\");"), chatbox.scripts)
        }

    @Test fun `a reset is done right away on a chatbox ready with the session, then reloads it`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            dispatcher.attach(chatbox.chatbox)
            chatbox.state.value = CrispChatboxState.Ready

            dispatcher.reset(key)

            assertEquals(listOf("reset"), chatbox.functions)
            assertEquals(1, chatbox.reloads)
            assertTrue(preferences.values.isEmpty())
        }

    @Test fun `a failed reset fails the calls made meanwhile, and is retried on the next preparation`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            chatbox.result = { throw JSEvaluationException.JSException("boom") }
            dispatcher.attach(chatbox.chatbox)
            chatbox.state.value = CrispChatboxState.Ready

            dispatcher.reset(key)
            val nickname = runCatching {
                dispatcher.withSession(config, chatbox.bridge) { chatbox.bridge.getUserNickname() }
            }

            assertTrue(nickname.exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("reset", "reset"), chatbox.functions)
            assertEquals(0, chatbox.reloads)
            assertEquals(CrispControllerDispatcher.Result.RESET_PENDING, dispatcher.dispatch(config, chatbox.bridge))
        }

    @Test fun `calls are dispatched when a chatbox becomes ready with the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            dispatcher.attach(chatbox.chatbox)
            dispatcher.add(key, CrispSessionCall.SetNickname("John"))
            assertTrue(chatbox.scripts.isEmpty())

            chatbox.state.value = CrispChatboxState.Ready

            assertEquals(listOf("setUserNickname"), chatbox.functions)
        }

    @Test fun `calls made while one is dispatched are dispatched after it, not deduplicated with it`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            val answer = CompletableDeferred<String>()
            chatbox.result = { script -> if ("John" in script) answer.await() else "null" }
            dispatcher.attach(chatbox.chatbox)
            chatbox.state.value = CrispChatboxState.Ready

            dispatcher.add(key, CrispSessionCall.SetNickname("John"))
            dispatcher.add(key, CrispSessionCall.SetNickname("Jane"))
            dispatcher.add(key, CrispSessionCall.SetNickname("Jack"))
            answer.complete("null")

            assertEquals(
                listOf(
                    "return await CrispClient.setUserNickname(\"John\");",
                    "return await CrispClient.setUserNickname(\"Jack\");",
                ),
                chatbox.scripts,
            )
            assertTrue(preferences.values.isEmpty())
        }

    @Test fun `an idempotent call interrupted by a page unload is put back, before those made meanwhile`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            val answer = CompletableDeferred<String>()
            chatbox.result = { script -> if ("plan" in script) answer.await() else "null" }
            dispatcher.add(key, CrispSessionCall.SetData(mapOf("plan" to JsonPrimitive("free"))))

            val result = async { dispatcher.dispatch(config, chatbox.bridge) }
            dispatcher.add(key, event("upgraded"))
            dispatcher.add(key, CrispSessionCall.SetData(mapOf("seats" to JsonPrimitive(3))))
            answer.completeExceptionally(JSEvaluationException.PageUnloaded())
            assertEquals(CrispControllerDispatcher.Result.PAGE_UNLOADED, result.await())

            chatbox.scripts.clear()
            chatbox.result = { "null" }
            dispatcher.dispatch(config, chatbox.bridge)
            assertEquals(
                listOf(
                    "return await CrispClient.setSessionEvent(\"upgraded\", null, null);",
                    "return await CrispClient.setSessionData([[\"plan\",\"free\"],[\"seats\",3]]);",
                ),
                chatbox.scripts,
            )
        }

    @Test fun `an idempotent call whose dispatch is canceled is put back`() = runTest(UnconfinedTestDispatcher()) {
        val chatbox = FakeChatbox(config)
        val answer = CompletableDeferred<String>()
        chatbox.result = { script -> if ("John" in script) answer.await() else "null" }
        dispatcher.add(key, CrispSessionCall.SetNickname("John"))

        val result = async { dispatcher.dispatch(config, chatbox.bridge) }
        result.cancel()

        chatbox.scripts.clear()
        chatbox.result = { "null" }
        assertEquals(CrispControllerDispatcher.Result.DISPATCHED, dispatcher.dispatch(config, chatbox.bridge))
        assertEquals(listOf("return await CrispClient.setUserNickname(\"John\");"), chatbox.scripts)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun `an event whose dispatch is canceled is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val chatbox = FakeChatbox(config)
        val answer = CompletableDeferred<String>()
        chatbox.result = { script -> if ("\"1\"" in script) answer.await() else "null" }
        dispatcher.add(key, event("1"))
        dispatcher.add(key, event("2"))

        val result = async { dispatcher.dispatch(config, chatbox.bridge) }
        result.cancel()

        chatbox.scripts.clear()
        assertEquals(CrispControllerDispatcher.Result.DISPATCHED, dispatcher.dispatch(config, chatbox.bridge))
        assertEquals(listOf("return await CrispClient.setSessionEvent(\"2\", null, null);"), chatbox.scripts)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun `a call interrupted by a page unload is dropped if a reset is requested meanwhile`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            val answer = CompletableDeferred<String>()
            chatbox.result = { answer.await() }
            dispatcher.add(key, CrispSessionCall.SetNickname("John"))

            val result = async { dispatcher.dispatch(config, chatbox.bridge) }
            dispatcher.reset(key)
            answer.completeExceptionally(JSEvaluationException.PageUnloaded())
            result.await()

            assertEquals(CrispControllerDispatcher.Result.RESET_PENDING, dispatcher.dispatch(config, chatbox.bridge))
            dispatcher.onSessionReset(config)
            assertTrue(preferences.values.isEmpty())
        }

    @Test fun `other calls fail as not ready if the page is unloaded while dispatching the calls waiting`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            val answer = CompletableDeferred<String>()
            chatbox.result = { script -> if ("setUserNickname" in script) answer.await() else "null" }
            dispatcher.attach(chatbox.chatbox)
            chatbox.state.value = CrispChatboxState.Ready
            dispatcher.add(key, CrispSessionCall.SetNickname("John"))

            val nickname = async {
                runCatching { dispatcher.withSession(config, chatbox.bridge) { chatbox.bridge.getUserNickname() } }
            }
            chatbox.state.value = CrispChatboxState.Loading
            answer.completeExceptionally(JSEvaluationException.PageUnloaded())

            assertTrue(nickname.await().exceptionOrNull() is CrispControllerDispatcher.SessionNotReady)
            assertEquals(listOf("setUserNickname"), chatbox.functions)
        }

    @Test fun `other calls are made once the calls waiting for the session are dispatched`() =
        runTest(UnconfinedTestDispatcher()) {
            val chatbox = FakeChatbox(config)
            val answer = CompletableDeferred<String>()
            chatbox.result = { script -> if ("setUserNickname" in script) answer.await() else "null" }
            dispatcher.attach(chatbox.chatbox)
            chatbox.state.value = CrispChatboxState.Ready
            dispatcher.add(key, CrispSessionCall.SetNickname("John"))
            dispatcher.add(key, CrispSessionCall.SetEmail("john@example.com", null))

            val nickname = async { dispatcher.withSession(config, chatbox.bridge) { chatbox.bridge.getUserNickname() } }
            assertEquals(listOf("setUserNickname"), chatbox.functions)

            answer.complete("null")
            nickname.await()
            assertEquals(listOf("setUserNickname", "setUserEmail", "getUserNickname"), chatbox.functions)
        }

    @Test fun `sessions are dispatched separately`() = runTest(UnconfinedTestDispatcher()) {
        val chatbox = FakeChatbox(config)
        dispatcher.add(other.session, CrispSessionCall.SetNickname("Jane"))
        dispatcher.add(key, CrispSessionCall.SetNickname("John"))

        dispatcher.dispatch(config, chatbox.bridge)

        assertEquals(listOf("return await CrispClient.setUserNickname(\"John\");"), chatbox.scripts)
        assertEquals(1, preferences.values.size)
    }

    @Test fun `unreadable calls are dropped`() = runTest(UnconfinedTestDispatcher()) {
        dispatcher.add(key, CrispSessionCall.SetNickname("John"))
        preferences.values.keys.forEach { preferences.values[it] = "{corrupted" }

        val chatbox = FakeChatbox(config)
        assertEquals(CrispControllerDispatcher.Result.DISPATCHED, dispatcher.dispatch(config, chatbox.bridge))
        assertTrue(chatbox.scripts.isEmpty())
    }
}
