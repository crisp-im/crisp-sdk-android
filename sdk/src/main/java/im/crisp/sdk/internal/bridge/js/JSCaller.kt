package im.crisp.sdk.internal.bridge.js

import im.crisp.sdk.internal.bridge.types.bridgeJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer

/**
 * Runs a JS function body and returns its result as JSON text (`"null"` for `undefined`/`null`).
 * The Swift side returns `Any?` and sorts out types afterwards; with `evaluateJavascript` on
 * Android everything comes back as text anyway, so we make the contract explicit: JSON in, JSON out.
 */
internal typealias JSEvaluator = suspend (javaScript: String) -> String

internal sealed class JSEvaluationException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class JSException(message: String) : JSEvaluationException("JS exception: $message")

    class PageUnloaded : JSEvaluationException("JS page unloaded before answering")

    class TypeMismatch(json: String, cause: Throwable) : JSEvaluationException("JS evaluation result could not be converted to the expected type: $json", cause)
}

/** JSON-encodes an argument to a JS-source string (a JSON literal is a valid JS expression). */
internal inline fun <reified T> jsEncode(value: T): String = bridgeJson.encodeToString(serializer<T>(), value)

internal class JSCaller(private val funcName: String, private val evaluator: JSEvaluator) {
    suspend inline operator fun <reified T> invoke(vararg args: String): T =
        decode(serializer<T>(), call(args.toList()))

    @PublishedApi
    internal suspend fun call(args: List<String>): String = evaluator(
        "return await $funcName(${args.joinToString(", ")});",
    )

    @PublishedApi
    internal fun <T> decode(
        strategy: kotlinx.serialization.KSerializer<T>,
        json: String,
    ): T {
        // JS functions returning nothing yield "null", which Unit's serializer cannot read: it expects "{}"
        val source = if (strategy.descriptor == Unit.serializer().descriptor) "{}" else json
        return try {
            bridgeJson.decodeFromString(strategy, source)
        } catch (e: SerializationException) {
            throw JSEvaluationException.TypeMismatch(json, e)
        } catch (e: IllegalArgumentException) {
            throw JSEvaluationException.TypeMismatch(json, e)
        }
    }
}
