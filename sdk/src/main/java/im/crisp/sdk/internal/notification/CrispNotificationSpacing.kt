package im.crisp.sdk.internal.notification

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Spaces the updates of the tokens of a session by [spacing],
 * as the Crisp servers do not make them atomically, so that one is not lost (see [CrispNotificationSync]).
 *
 * Process-wide ([instance]), as several chatboxes can update the same session at once,
 * e.g. an embedded one and the sheet of a notification tapped, or a view recreated.
 * Each update reserves its time before waiting, so that concurrent updates are spaced from each other too.
 */
internal class CrispNotificationSpacing(
    private val spacing: Duration = SAME_SESSION_SPACING,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    // The time reserved for the last update of each session, until spaced enough
    private val reserved = mutableMapOf<String, TimeMark>()

    /** Waits for the time reserved for the next update of [sessionId], right away if not updated lately. */
    suspend fun await(sessionId: String) {
        val wait = reserve(sessionId)
        if (wait.isPositive()) delay(wait)
    }

    /** Reserves the time of the next update of [sessionId], returning how long to wait for it. */
    @Synchronized
    private fun reserve(sessionId: String): Duration {
        reserved.entries.removeAll { (_, mark) -> mark.elapsedNow() >= spacing }
        val wait = reserved[sessionId]?.let { spacing - it.elapsedNow() } ?: Duration.ZERO
        reserved[sessionId] = timeSource.markNow() + wait
        return wait
    }

    companion object {
        /**
         * Between two updates of the same session.
         * Never needed in the tests on devices, but kept as it costs nothing in practice: a session is rarely
         * updated twice in a row (e.g. a token change, or a reset right after loading), and in background.
         */
        val SAME_SESSION_SPACING = 2.seconds

        val instance = CrispNotificationSpacing()
    }
}
