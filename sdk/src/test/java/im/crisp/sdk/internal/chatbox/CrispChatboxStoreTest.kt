package im.crisp.sdk.internal.chatbox

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrispChatboxStoreTest {
    @Test fun `a held page runs the action instead of becoming ready`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        val clientEvents = mutableListOf<CrispClientEvent>()
        backgroundScope.launch { store.clientEvents.collect { clientEvents += it } }
        var held = 0

        store.onPageStarted()
        store.holdNextReady { held++ }
        store.onPageFinished()

        assertEquals(1, held)
        assertEquals(CrispChatboxState.Loading, store.state.value)
        assertTrue(clientEvents.isEmpty())
    }

    @Test fun `the action becomes ready by finishing the page again`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        val clientEvents = mutableListOf<CrispClientEvent>()
        backgroundScope.launch { store.clientEvents.collect { clientEvents += it } }

        store.onPageStarted()
        store.holdNextReady { store.onPageFinished() }
        store.onSessionLoaded("session_a")
        store.onPageFinished()

        assertEquals(CrispChatboxState.Ready, store.state.value)
        assertEquals(listOf(CrispClientEvent.Loaded("session_a")), clientEvents)
    }

    @Test fun `loaded once both ready and its session loaded, in either order`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        val clientEvents = mutableListOf<CrispClientEvent>()
        backgroundScope.launch { store.clientEvents.collect { clientEvents += it } }

        // A restored session: ready first
        store.onPageStarted()
        store.onPageFinished()
        assertTrue(clientEvents.isEmpty())
        store.onSessionLoaded("session_a")

        // A new session: loaded first
        store.onPageStarted()
        store.onSessionLoaded("session_b")
        assertTrue(clientEvents.size == 1)
        store.onPageFinished()

        assertEquals(listOf(CrispClientEvent.Loaded("session_a"), CrispClientEvent.Loaded("session_b")), clientEvents)
    }

    @Test fun `loaded again for a session reset in the page, not when ready again`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        val clientEvents = mutableListOf<CrispClientEvent>()
        backgroundScope.launch { store.clientEvents.collect { clientEvents += it } }

        store.onPageStarted()
        store.onSessionLoaded("session_a")
        store.onPageFinished()
        // E.g. purged by an operator: the chat is initialized again for the new session
        store.onPageFinished()
        store.onSessionLoaded("session_b")

        assertEquals("session_b", store.sessionId.value)
        assertEquals(listOf(CrispClientEvent.Loaded("session_a"), CrispClientEvent.Loaded("session_b")), clientEvents)
    }

    @Test fun `the session is forgotten when another page starts`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)

        store.onSessionLoaded("session_a")
        store.onPageStarted()

        assertEquals(null, store.sessionId.value)
    }

    @Test fun `only the next page is held`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        var held = 0

        store.onPageStarted()
        store.holdNextReady { held++ }
        store.onPageFinished()
        store.onPageStarted()
        store.onPageFinished()

        assertEquals(1, held)
        assertEquals(CrispChatboxState.Ready, store.state.value)
    }

    @Test fun `the hold is dropped when another page starts`() = runTest(UnconfinedTestDispatcher()) {
        val store = CrispChatboxStore(backgroundScope)
        var held = 0

        store.holdNextReady { held++ }
        store.onPageStarted()
        store.onPageFinished()

        assertEquals(0, held)
        assertEquals(CrispChatboxState.Ready, store.state.value)
    }
}
