package im.crisp.sdk.internal.notification

import android.os.Handler
import android.os.Looper
import im.crisp.sdk.api.CrispNotificationListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The [listeners][CrispNotificationListener] of the notifications shown,
 * global as notifications are received while no chatbox is shown.
 *
 * Notified on the main thread ([mainExecutor]),
 * while notifications are received on a background thread.
 */
internal class CrispNotificationListeners(
    private val mainExecutor: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
) {
    // Copied on write, so that listeners can be registered from any thread,
    // and remove themselves while notified
    private val listeners = CopyOnWriteArrayList<CrispNotificationListener>()

    fun add(listener: CrispNotificationListener) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: CrispNotificationListener) {
        listeners.remove(listener)
    }

    /**
     * Notifies the listeners registered once on the main thread,
     * so that one removed meanwhile (e.g. with the `Activity` it references) is not.
     */
    fun notifyShown(data: Map<String, String>) {
        if (listeners.isEmpty()) return
        mainExecutor.execute {
            listeners.forEach { it.onNotificationReceived(data) }
        }
    }

    companion object {
        val instance = CrispNotificationListeners()
    }
}
