package im.crisp.sdk.internal.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrispWebViewVersionTest {

    @Test
    fun parseMajor_fromVersionName() {
        assertEquals(118, CrispWebViewVersion.parseMajor("118.0.5993.80"))
        assertEquals(66, CrispWebViewVersion.parseMajor("66.0.3359.126"))
        assertEquals(120, CrispWebViewVersion.parseMajor(" 120"))
    }

    @Test
    fun parseMajor_returnsNullWhenNotAVersion() {
        assertNull(CrispWebViewVersion.parseMajor(null))
        assertNull(CrispWebViewVersion.parseMajor(""))
        assertNull(CrispWebViewVersion.parseMajor("beta"))
        assertNull(CrispWebViewVersion.parseMajor("v118.0"))
    }

    @Test
    fun parseMajorFromUserAgent_readsChromeToken() {
        val userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A.240305.019.A1; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/118.0.5993.80 Mobile Safari/537.36"
        assertEquals(118, CrispWebViewVersion.parseMajorFromUserAgent(userAgent))
    }

    @Test
    fun parseMajorFromUserAgent_returnsNullWithoutChromeToken() {
        assertNull(CrispWebViewVersion.parseMajorFromUserAgent(null))
        assertNull(CrispWebViewVersion.parseMajorFromUserAgent("Mozilla/5.0 Firefox/120.0"))
    }

    @Test
    fun isSupported_comparesAgainstMinimum() {
        val minimum = CrispWebViewVersion.MIN_WEBVIEW_MAJOR
        assertFalse(CrispWebViewVersion.isSupported(minimum - 1))
        assertTrue(CrispWebViewVersion.isSupported(minimum))
        assertTrue(CrispWebViewVersion.isSupported(minimum + 12))
    }

    @Test
    fun isSupported_usesGivenRequiredVersion() {
        assertTrue(CrispWebViewVersion.isSupported(100, required = 100))
        assertFalse(CrispWebViewVersion.isSupported(99, required = 100))
    }

    @Test
    fun reportsSafeArea_onlySinceAndroid11AndWebView140() {
        assertFalse(CrispWebViewVersion.reportsSafeArea(29, 147))
        assertFalse(CrispWebViewVersion.reportsSafeArea(36, 133))
        assertTrue(CrispWebViewVersion.reportsSafeArea(30, 140))
        assertTrue(CrispWebViewVersion.reportsSafeArea(36, 147))
    }

    @Test
    fun reportsSafeArea_assumedWhenTheVersionIsUnknown() {
        assertFalse(CrispWebViewVersion.reportsSafeArea(29, null))
        assertTrue(CrispWebViewVersion.reportsSafeArea(30, null))
    }
}
