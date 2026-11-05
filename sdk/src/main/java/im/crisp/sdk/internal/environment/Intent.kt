package im.crisp.sdk.internal.environment

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

internal fun openExternalUrl(context: Context, url: String) {
    val customTabsIntent: CustomTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    customTabsIntent.launchUrl(context, url.toUri())
}