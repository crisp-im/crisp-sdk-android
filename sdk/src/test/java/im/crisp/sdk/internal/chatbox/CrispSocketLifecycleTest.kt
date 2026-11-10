package im.crisp.sdk.internal.chatbox

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CrispSocketLifecycleTest {
    private val calls = mutableListOf<String>()
    private val socket = CrispSocketLifecycle(
        connect = { calls += "connect" },
        disconnect = { calls += "disconnect" },
    )

    @Test fun `a ready page is not connected again`() = runTest {
        socket.update(shouldConnect = true, CrispChatboxState.Ready)

        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `a ready page is disconnected then connected again`() = runTest {
        socket.update(shouldConnect = false, CrispChatboxState.Ready)
        socket.update(shouldConnect = true, CrispChatboxState.Ready)

        assertEquals(listOf("disconnect", "connect"), calls)
    }

    @Test fun `a loading page is disconnected once ready`() = runTest {
        socket.update(shouldConnect = true, CrispChatboxState.Loading)
        socket.update(shouldConnect = false, CrispChatboxState.Ready)

        assertEquals(listOf("disconnect"), calls)
    }

    @Test fun `a reloaded page is considered connected again`() = runTest {
        socket.update(shouldConnect = false, CrispChatboxState.Ready)
        socket.update(shouldConnect = false, CrispChatboxState.Loading)
        socket.update(shouldConnect = true, CrispChatboxState.Ready)

        assertEquals(listOf("disconnect"), calls)
    }

    @Test fun `a page in error is neither connected nor disconnected`() = runTest {
        socket.update(shouldConnect = false, CrispChatboxState.Error(Exception()))
        socket.update(shouldConnect = true, CrispChatboxState.Error(Exception()))

        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `a page connected right away is disconnected again on the next update that should be`() = runTest {
        socket.update(shouldConnect = false, CrispChatboxState.Ready)
        socket.connectNow()
        socket.update(shouldConnect = false, CrispChatboxState.Ready)
        socket.update(shouldConnect = true, CrispChatboxState.Ready)

        assertEquals(listOf("disconnect", "connect", "disconnect", "connect"), calls)
    }
}
