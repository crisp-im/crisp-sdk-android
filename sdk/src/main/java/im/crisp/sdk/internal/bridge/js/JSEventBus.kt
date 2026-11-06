package im.crisp.sdk.internal.bridge.js

import im.crisp.sdk.internal.bridge.types.bridgeJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

/**
 * Type-erased event handler: a typed `(T) -> Unit` plus the JSON decoding step that feeds it from
 * the raw string posted by the web page. Decode failures are thrown as [JSEventException] and routed
 * by [JSEventBus] to its error sink.
 */
internal class JSEventHandler private constructor(private val inner: (Any?) -> Unit) {
    @Throws(JSEventException::class)
    fun handle(body: Any?) = inner(body)

    companion object {
        fun <T> of(strategy: DeserializationStrategy<T>, callback: (T) -> Unit) =
            JSEventHandler { body ->
                val bodyString = body as? String ?: throw JSEventException.BadSerialization()
                val decoded = try {
                    bridgeJson.decodeFromString(strategy, bodyString)
                } catch (e: SerializationException) {
                    throw JSEventException.DecodingException(
                        "JS message body could not be decoded from \"$bodyString\": $e",
                    )
                } catch (e: IllegalArgumentException) {
                    throw JSEventException.DecodingException("$e")
                }
                callback(decoded)
            }

        /** For events whose payload is ignored. */
        fun of(callback: () -> Unit) = JSEventHandler { callback() }
    }
}

/** Routes `window.webkit.messageHandlers.<channel>.postMessage(json)` calls to handlers. */
internal class JSEventBus {
    private val handlers = HashMap<String, JSEventHandler>()
    private var errorSink: ((channel: String, error: JSEventException) -> Unit)? = null

    /** Registering twice on the same channel replaces the previous handler. */
    fun register(channel: String, handler: JSEventHandler) {
        handlers[channel] = handler
    }

    fun onError(sink: (channel: String, error: JSEventException) -> Unit) {
        errorSink = sink
    }

    /** Must be called on the main thread. */
    internal fun dispatch(channel: String, body: Any?) {
        val handler = handlers[channel] ?: return
        try {
            handler.handle(body)
        } catch (e: JSEventException) {
            errorSink?.invoke(channel, e)
        }
    }
}

internal inline fun <reified T> JSEventBus.register(
    channel: String,
    noinline callback: (T) -> Unit,
) = register(channel, JSEventHandler.of(kotlinx.serialization.serializer<T>(), callback))
