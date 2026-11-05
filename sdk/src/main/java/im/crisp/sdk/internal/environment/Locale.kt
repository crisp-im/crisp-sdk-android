package im.crisp.sdk.internal.environment

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

internal fun getUserLocales(context: Context): List<String> {
    var userLocales = getApplicationLocales()
    if (userLocales.isEmpty()) {
        userLocales = getConfigurationLocales(context)
        if (userLocales.isEmpty()) {
            userLocales = listOf(Locale.US.toLanguageTag())
        }
    }
    return userLocales
}

private fun getApplicationLocales(): List<String> {
    val localeList = AppCompatDelegate.getApplicationLocales()
    if (localeList.isEmpty) {
        return emptyList()
    } else {
        val localeListSize = localeList.size()
        val applicationLocales = ArrayList<String>(localeListSize)
        for (i in 0..<localeListSize) {
            localeList.get(i)?.toLanguageTag()?.let { applicationLocales.add(it) }
        }
        return applicationLocales
    }
}

private fun getConfigurationLocales(context: Context): List<String> {
    val localeList = context.resources.configuration.getLocales()
    if (localeList.isEmpty) {
        return emptyList()
    } else {
        val localeListSize = localeList.size()
        val configurationLocales = ArrayList<String>(localeListSize)
        for (i in 0..<localeListSize) {
            configurationLocales.add(localeList.get(i).toLanguageTag())
        }
        return configurationLocales
    }
}
