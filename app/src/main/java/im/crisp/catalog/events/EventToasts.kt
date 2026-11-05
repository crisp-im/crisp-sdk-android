@file:JvmName("EventToasts")

package im.crisp.catalog.events

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import im.crisp.catalog.R
import im.crisp.sdk.api.CrispEventCallbacks
import im.crisp.sdk.model.Message

private const val LOG_TAG = "CrispEvents"

/** Logs the [event] string (formatted with [formatArgs]) of the chatbox shown by [source], and toasts it. */
fun Context.toastEvent(source: String, @StringRes event: Int, vararg formatArgs: Any) {
    val text = getString(R.string.event_toast, source, getString(event, *formatArgs))
    Log.i(LOG_TAG, text)
    Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}

/** A short description of [message], e.g. `TextContent from OPERATOR`. */
fun Context.describe(message: Message): String =
    getString(R.string.event_message_description, message.content::class.simpleName, message.from)

/** Toasts every event of the chatbox shown by [source]. */
class ToastEventCallbacks(private val context: Context, private val source: String) : CrispEventCallbacks {
    override fun onChatOpened() = context.toastEvent(source, R.string.event_chat_opened)

    override fun onChatClosed() = context.toastEvent(source, R.string.event_chat_closed)

    override fun onSessionLoaded(sessionId: String) =
        context.toastEvent(source, R.string.event_session_loaded, sessionId)

    override fun onMessageSent(message: Message) =
        context.toastEvent(source, R.string.event_message_sent, context.describe(message))

    override fun onMessageReceived(message: Message) =
        context.toastEvent(source, R.string.event_message_received, context.describe(message))
}

/** Toasts the events of the chatbox shown by [source], for the Compose event lambdas. */
class EventToaster(private val context: Context, private val source: String) {
    operator fun invoke(@StringRes event: Int, vararg formatArgs: Any) =
        context.toastEvent(source, event, *formatArgs)

    /** See [Context.describe]. */
    fun describe(message: Message): String = context.describe(message)
}

/** Returns a toaster for the events of the chatbox shown by [source], for the Compose event lambdas. */
@Composable
fun rememberEventToaster(source: String): EventToaster {
    val context = LocalContext.current
    return remember(context, source) { EventToaster(context, source) }
}
