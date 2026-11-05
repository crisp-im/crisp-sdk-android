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
        store.onPageFinished()

        assertEquals(CrispChatboxState.Ready, store.state.value)
        assertEquals(listOf(CrispClientEvent.Loaded), clientEvents)
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
