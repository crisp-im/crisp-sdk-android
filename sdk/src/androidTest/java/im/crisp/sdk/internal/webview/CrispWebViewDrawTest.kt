package im.crisp.sdk.internal.webview

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.crisp.sdk.api.CrispConfig
import im.crisp.sdk.internal.chatbox.CrispChatboxStore
import im.crisp.sdk.internal.notification.TestScreenActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How long [CrispWebView.runWhenDrawn] waits for a page to be drawn where the WebView may not draw
 * (not simulated by Robolectric): right away once drawn, at most its timeout otherwise.
 *
 * Named in camelCase, as names with spaces are not allowed in the DEX files before Android 11.
 */
@RunWith(AndroidJUnit4::class)
class CrispWebViewDrawTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<TestScreenActivity>
    private lateinit var webView: CrispWebView

    @After fun tearDown() {
        scenario.close()
    }

    /** Shows a WebView loading a page in [TestScreenActivity], set up by [configure]. */
    private fun show(configure: (CrispWebView, FrameLayout) -> Unit = { _, _ -> }) {
        scenario = ActivityScenario.launch(TestScreenActivity::class.java)
        scenario.onActivity { activity ->
            val container = FrameLayout(activity)
            webView = CrispWebView(activity).apply {
                setupWebView(CrispChatboxStore()) {}
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            container.addView(webView)
            activity.setContentView(container)
            configure(webView, container)
            webView.load(CrispConfig("website"))
        }
        // As the Web client is ready once started
        SystemClock.sleep(PAGE_READY_MS)
    }

    /** How long [CrispWebView.runWhenDrawn] takes to run its action, in milliseconds. */
    private fun measure(case: String): Long {
        val latch = CountDownLatch(1)
        var start = 0L
        instrumentation.runOnMainSync {
            start = SystemClock.uptimeMillis()
            webView.runWhenDrawn { latch.countDown() }
        }
        assertTrue("$case: never run", latch.await(5, TimeUnit.SECONDS))
        return (SystemClock.uptimeMillis() - start).also { Log.i(LOG_TAG, "$case: run after $it ms") }
    }

    @Test fun shownIsRunOnceDrawn() {
        show()
        val elapsed = measure("shown")
        assertTrue("Shown: $elapsed ms", elapsed < TIMEOUT_MS)
    }

    @Test fun zeroHeightIsRunWithinTimeout() {
        show { webView, _ -> webView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, 0) }
        val elapsed = measure("zero height")
        assertTrue("Zero height: $elapsed ms", elapsed <= TIMEOUT_MS + MARGIN_MS)
    }

    @Test fun offScreenIsRunWithinTimeout() {
        // E.g. a sheet below the screen before it opens, or a pager page next to the selected one
        show { webView, _ -> webView.translationY = 10_000f }
        val elapsed = measure("off-screen")
        assertTrue("Off-screen: $elapsed ms", elapsed <= TIMEOUT_MS + MARGIN_MS)
    }

    @Test fun stoppedIsRunWithinTimeout() {
        show()
        // Another screen above stops the one of the WebView
        scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, TestScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        SystemClock.sleep(PAGE_READY_MS)
        val elapsed = measure("stopped")
        assertTrue("Stopped: $elapsed ms", elapsed <= TIMEOUT_MS + MARGIN_MS)
        instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .forEach(Activity::finish)
        }
    }

    private companion object {
        const val LOG_TAG = "CrispWebViewDrawTest"
        const val PAGE_READY_MS = 1500L
        // CrispWebView.VISUAL_STATE_TIMEOUT_MS
        const val TIMEOUT_MS = 1000L
        const val MARGIN_MS = 200L
    }
}
