package im.crisp.sdk.internal.environment

import android.util.Log
import im.crisp.sdk.api.Crisp
import im.crisp.sdk.api.CrispLogger
import im.crisp.sdk.internal.bridge.LogEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {
    /** Records the received logs, as `priority|tag|message`. */
    private class RecordingLogger : CrispLogger {
        val logs = mutableListOf<String>()

        override fun log(priority: Int, tag: String, message: String) {
            logs += "$priority|$tag|$message"
        }
    }

    private val first = RecordingLogger()
    private val second = RecordingLogger()

    @After
    fun tearDown() {
        Crisp.removeLogger(first)
        Crisp.removeLogger(second)
        Crisp.setLogLevel(Log.VERBOSE)
    }

    @Test
    fun `registered loggers receive the SDK logs`() {
        Crisp.addLogger(first)
        Crisp.addLogger(second)

        log(Level.WARN, "Tag", "Message")

        assertEquals(listOf("${Log.WARN}|Tag|Message"), first.logs)
        assertEquals(listOf("${Log.WARN}|Tag|Message"), second.logs)
    }

    @Test
    fun `registered loggers receive the Web client logs`() {
        Crisp.addLogger(first)

        log(LogEvent.Level.LOG, "[Crisp] [Session]", "Loaded")

        assertEquals(listOf("${Log.VERBOSE}|CrispClient|[Session] Loaded"), first.logs)
    }

    @Test
    fun `registering twice logs once`() {
        Crisp.addLogger(first)
        Crisp.addLogger(first)

        log(Level.INFO, "Tag", "Message")

        assertEquals(1, first.logs.size)
    }

    @Test
    fun `removed loggers no longer receive logs`() {
        Crisp.addLogger(first)
        Crisp.addLogger(second)
        Crisp.removeLogger(first)

        log(Level.ERROR, "Tag", "Message")

        assertTrue(first.logs.isEmpty())
        assertEquals(1, second.logs.size)
    }

    @Test
    fun `loggers can remove themselves while logging`() {
        val selfRemoving = object : CrispLogger {
            var count = 0

            override fun log(priority: Int, tag: String, message: String) {
                count++
                Crisp.removeLogger(this)
            }
        }
        Crisp.addLogger(selfRemoving)
        Crisp.addLogger(first)

        log(Level.DEBUG, "Tag", "First")
        log(Level.DEBUG, "Tag", "Second")

        assertEquals(1, selfRemoving.count)
        assertEquals(2, first.logs.size)
    }

    @Test
    fun `errors are logged with their stack trace`() {
        Crisp.addLogger(first)

        log(Level.ERROR, "Tag", "Message", IllegalStateException("Boom"))

        // android.util.Log.getStackTraceString returns null in unit tests
        assertEquals(listOf("${Log.ERROR}|Tag|Message\nnull"), first.logs)
    }

    @Test
    fun `logs below the log level are not logged`() {
        Crisp.addLogger(first)
        Crisp.setLogLevel(Log.WARN)

        log(Level.INFO, "Tag", "Info")
        log(Level.WARN, "Tag", "Warn")
        log(Level.ERROR, "Tag", "Error")
        log(LogEvent.Level.DEBUG, "[Crisp]", "Debug")
        log(LogEvent.Level.ERROR, "[Crisp]", "Error")

        assertEquals(
            listOf(
                "${Log.WARN}|Tag|Warn",
                "${Log.ERROR}|Tag|Error",
                "${Log.ERROR}|CrispClient|[Crisp] Error",
            ),
            first.logs,
        )
    }

    @Test
    fun `a log level above assert logs nothing`() {
        Crisp.addLogger(first)
        Crisp.setLogLevel(Int.MAX_VALUE)

        log(Level.ERROR, "Tag", "Message")
        log(LogEvent.Level.ERROR, "[Crisp]", "Message")

        assertTrue(first.logs.isEmpty())
    }
}
