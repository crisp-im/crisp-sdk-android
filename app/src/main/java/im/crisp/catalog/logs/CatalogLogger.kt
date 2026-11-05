package im.crisp.catalog.logs

import android.util.Log
import im.crisp.sdk.api.CrispLogger

/**
 * Forwards the Crisp logs to Logcat, prefixing their tag (e.g. `Catalog/CrispClient`),
 * to show that they go through the app instead of the default Crisp logger.
 */
class CatalogLogger : CrispLogger {
    override fun log(priority: Int, tag: String, message: String) {
        Log.println(priority, "$TAG_PREFIX$tag", message)
    }

    private companion object {
        private const val TAG_PREFIX = "Catalog/"
    }
}
