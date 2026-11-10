package im.crisp.sdk.internal.webview

import im.crisp.sdk.internal.bridge.CrispClientBridge
import im.crisp.sdk.internal.bridge.InterfaceActionEvent
import im.crisp.sdk.internal.bridge.InterfaceActionEvent.Action
import im.crisp.sdk.internal.bridge.InterfaceLifecycleEvent
import im.crisp.sdk.internal.bridge.LogEvent
import im.crisp.sdk.internal.bridge.types.copy
import im.crisp.sdk.internal.bridge.types.toModel
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.chatbox.CrispChatboxEvent
import im.crisp.sdk.internal.chatbox.CrispClientEvent
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log

private const val LOG_TAG = "CrispClientBridge"

/**
 * Logged by the Web client when its socket connects or disconnects, which it tells no other way.
 *
 * Stopgap until `connectSocket` resolves once connected, to remove then.
 */
private val SOCKET_STATE_LOG = Regex("""^Socket state has been changed to connected: (true|false)\b""")

/** Whether the socket is connected if [value] is a socket state log, else `null`. */
internal fun parseSocketStateLog(value: String): Boolean? =
    SOCKET_STATE_LOG.find(value)?.groupValues?.get(1)?.toBooleanStrict()

/** Forwards the events sent by the Web client to the chatbox [store][CrispChatboxStore]. */
internal fun CrispClientBridge.bindTo(store: CrispChatboxStore) {
    events.onError { channel, error ->
        log(Level.WARN, LOG_TAG, "Cannot handle '$channel' event: ${error.message}")
    }

    onLog {
        log(it.level, it.ns, it.value)
        parseSocketStateLog(it.value)?.let(store::onSocketStateChanged)
    }

    onScriptError {
        val location = "${it.filename} (${it.line}:${it.col})"
        log(
            LogEvent.Level.ERROR,
            "[${it.source.name.lowercase()}]",
            "${it.message}, source: $location\nStacktrace: ${it.stack}",
        )
    }

    onInterfaceLifecycle {
        if (it.state == InterfaceLifecycleEvent.State.READY) {
            store.onPageFinished()
        }
    }

    onMessageSent { message ->
        message.toModel()?.let { store.emitClientEvent(CrispClientEvent.MessageSent(it)) }
    }

    onMessageReceived { message ->
        message.toModel()?.let { store.emitClientEvent(CrispClientEvent.MessageReceived(it)) }
    }

    onInterfaceAction { event ->
        when (event.action) {
            Action.CLOSE -> store.emitEvent(CrispChatboxEvent.Close)
            Action.DOWNLOAD -> event.data?.file?.url?.let {
                store.emitEvent(CrispChatboxEvent.OpenExternalUrl(it))
            }
            Action.SELECT -> {
                val data = event.data
                val pointer = data?.pointer
                val text = data?.target?.takeIf {
                    data.`object` == InterfaceActionEvent.Data.Object.MESSAGE
                }?.content?.copy()
                if (pointer != null && !text.isNullOrEmpty()) {
                    store.emitEvent(CrispChatboxEvent.ShowCopyMenu(text, pointer.x, pointer.y))
                }
            }
            Action.UPLOAD, Action.SPEECH -> Unit
        }
    }
}
