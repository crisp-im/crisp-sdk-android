package im.crisp.sdk.internal.bridge.js

import android.content.ContextWrapper
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stub WebView (Android methods do nothing in unit tests) recording the evaluated scripts. */
internal class RecordingWebView : WebView(ContextWrapper(null)) {
    val scripts = mutableListOf<String>()

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        scripts += script
    }
}

/** Answers the call [id] as the page does, with the JSON [value]. */
internal fun WebViewBridge.resolve(id: Int, value: String) =
    onMessage("""{"kind":"resolve","id":$id,"value":${jsEncode(value)}}""")

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewBridgeTest {
    private val bridge = WebViewBridge(RecordingWebView())

    @Test fun `calls are answered by the page`() = runTest(UnconfinedTestDispatcher()) {
        val result = async { bridge.evaluator("return 1;") }

        bridge.resolve(0, "1")

        assertEquals("1", result.await())
    }

    @Test fun `rejecting pending calls fails them as unloaded`() = runTest(UnconfinedTestDispatcher()) {
        val first = async { runCatching { bridge.evaluator("return 1;") } }
        val second = async { runCatching { bridge.evaluator("return 2;") } }

        bridge.rejectPendingCalls()

        assertTrue(first.await().exceptionOrNull() is JSEvaluationException.PageUnloaded)
        assertTrue(second.await().exceptionOrNull() is JSEvaluationException.PageUnloaded)
    }

    @Test fun `answers of rejected calls are ignored`() = runTest(UnconfinedTestDispatcher()) {
        val rejected = async { runCatching { bridge.evaluator("return 1;") } }
        bridge.rejectPendingCalls()
        val next = async { bridge.evaluator("return 2;") }

        // Late answer of the unloaded page, then the answer of the next call
        bridge.resolve(0, "1")
        bridge.resolve(1, "2")

        assertTrue(rejected.await().exceptionOrNull() is JSEvaluationException.PageUnloaded)
        assertEquals("2", next.await())
    }
}
