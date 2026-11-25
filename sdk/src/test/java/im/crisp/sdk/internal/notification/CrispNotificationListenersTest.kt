package im.crisp.sdk.internal.notification

import im.crisp.sdk.api.notification.CrispNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrispNotificationListenersTest {
    // Runs what is posted to the main thread when flushed
    private val posted = mutableListOf<Runnable>()
    private val listeners = CrispNotificationListeners { posted += it }
    private val data = mapOf("sender" to "crisp", "website_id" to "website", "session_id" to "session_a")

    private fun flush() {
        posted.toList().also { posted.clear() }.forEach(Runnable::run)
    }

    @Test fun `listeners receive the data of the notification shown, on the main thread`() {
        val received = mutableListOf<Map<String, String>>()
        listeners.add { received += it }

        listeners.notifyShown(data)
        assertTrue(received.isEmpty())
        flush()

        assertEquals(listOf(data), received)
    }

    @Test fun `a listener is only registered once`() {
        var count = 0
        val listener = CrispNotificationListener { count++ }
        listeners.add(listener)
        listeners.add(listener)

        listeners.notifyShown(data)
        flush()

        assertEquals(1, count)
    }

    @Test fun `a listener removed before the main thread runs is not notified`() {
        var count = 0
        val listener = CrispNotificationListener { count++ }
        listeners.add(listener)

        listeners.notifyShown(data)
        listeners.remove(listener)
        flush()

        assertEquals(0, count)
    }

    @Test fun `nothing is posted without listener`() {
        listeners.notifyShown(data)

        assertTrue(posted.isEmpty())
    }

    @Test fun `a listener can remove itself while notified`() {
        val received = mutableListOf<String>()
        lateinit var first: CrispNotificationListener
        first = CrispNotificationListener { received += "first"; listeners.remove(first) }
        listeners.add(first)
        listeners.add { received += "second" }

        listeners.notifyShown(data)
        flush()
        listeners.notifyShown(data)
        flush()

        assertEquals(listOf("first", "second", "second"), received)
    }
}
