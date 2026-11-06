package im.crisp.sdk.internal.bridge.js

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JSCallerTest {
    @Test fun `unit result ignores the JS value`() = runTest {
        val result: Unit = JSCaller("f", { "null" })()
        assertEquals(Unit, result)
    }

    @Test fun `nullable result decodes null`() = runTest {
        val result: String? = JSCaller("f", { "null" })()
        assertNull(result)
    }

    @Test fun `result is decoded`() = runTest {
        val result: Boolean = JSCaller("f", { "true" })()
        assertEquals(true, result)
    }
}
