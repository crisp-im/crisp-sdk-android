package im.crisp.sdk.internal.bridge.js

import android.webkit.WebView
import im.crisp.sdk.internal.environment.Level
import im.crisp.sdk.internal.environment.log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
* Glues a [WebView] to the JSON bridge, installed by `CrispWebView.setupWebView`.
*
*  - JS → native: the page calls `CrispNative.postMessage(string)`. The string is a small JSON
*    envelope (`event` / `resolve` / `reject`). `addWebMessageListener` is used rather than
*    `addJavascriptInterface` so the channel is only exposed to our own origin, not to iframes of
*    the chat widget.
*  - native → JS: [evaluator] wraps the script in an async IIFE, because `evaluateJavascript`
*    cannot await Promises. The outcome travels back through the same channel, keyed by a call id.
*/
internal class WebViewBridge(
    private val webView: WebView,
    internal val events: JSEventBus = JSEventBus(),
) {
    private var nextCallbackId = 0
    private val pendingResults = HashMap<Int, CancellableContinuation<String>>()

    /**
     * The page whose events are dispatched, see [startPage]: `null` while none is loaded.
     * Main-thread only.
     */
    private var pageId: String? = null

    /**
     * A page is loading: only its events are dispatched from now on,
     * tagged with the returned id by the page (`{{PAGE_ID}}` in `index.html`).
     * Those still sent by the page replaced are dropped,
     * as they arrive once the next page started (e.g. its session joined),
     * which would be taken for the next page's.
     * Main-thread only.
     */
    fun startPage(): String = UUID.randomUUID().toString().also { pageId = it }

    /**
    * No page is loaded anymore (e.g. cleared). The events of the page replaced are dropped.
    * Main-thread only.
    */
    fun endPage() {
        pageId = null
    }

    /** Main-thread only. */
    val evaluator: JSEvaluator = { script ->
        suspendCancellableCoroutine { continuation ->
            val callbackId = nextCallbackId++
            pendingResults[callbackId] = continuation
            continuation.invokeOnCancellation { pendingResults.remove(callbackId) }
            webView.evaluateJavascript(wrapScript(script, callbackId), null)
        }
    }

    /**
     * Wraps [body] in an async IIFE (Immediately Invoked Function Expression, a function defined and
     * called in the same statement). `evaluateJavascript` runs top-level code where `await` is not
     * allowed and its callback cannot wait for a Promise, so the IIFE awaits the result and posts
     * it (or the error) back through [JS_OBJECT_NAME], tagged with the [callbackId].
     */
    private fun wrapScript(body: String, callbackId: Int) = """
        (async () => {
            try {
                const result = await (async () => { $body })();
                $JS_OBJECT_NAME.postMessage(JSON.stringify({
                    kind: 'resolve', id: $callbackId,
                    value: JSON.stringify(result === undefined ? null : result),
                }));
            } catch (e) {
                $JS_OBJECT_NAME.postMessage(JSON.stringify({
                    kind: 'reject', id: $callbackId, message: String((e && e.message) || e),
                }));
            }
        })();
    """.trimIndent()

    /**
     * Rejects the pending calls, as their page is replaced or destroyed and would never answer them.
     * Main-thread only.
     */
    fun rejectPendingCalls() {
        val continuations = pendingResults.values.toList()
        pendingResults.clear()
        continuations.forEach { it.resumeWithException(JSEvaluationException.PageUnloaded()) }
    }

    /** Entry point for the web message listener, main thread. */
    fun onMessage(raw: String) {
        val envelope = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            log(Level.WARN, LOG_TAG, "Ignoring web message, envelope is not a JSON object", e)
            return
        }
        when (envelope["kind"]?.jsonPrimitive?.content) {
            "event" -> {
                val page = envelope["page"]?.jsonPrimitive?.content
                if (page == null || page != pageId) return
                events.dispatch(
                    channel = envelope["channel"]?.jsonPrimitive?.content ?: return,
                    body = envelope["body"]?.jsonPrimitive?.content,
                )
            }
            "resolve" -> pendingResults.remove(envelope.callbackId())?.resume(
                envelope["value"]?.jsonPrimitive?.content ?: "null",
            )
            "reject" -> pendingResults.remove(envelope.callbackId())?.resumeWithException(
                JSEvaluationException.JSException(envelope["message"]?.jsonPrimitive?.content ?: "unknown"),
            )
        }
    }

    private fun JsonObject.callbackId() = this["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1

    companion object {
        private const val LOG_TAG = "WebViewBridge"

        /** Name of the object injected by `addWebMessageListener`, see `CrispWebView.setupWebView`. */
        const val JS_OBJECT_NAME = "CrispNative"
    }
}
