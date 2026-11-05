package im.crisp.catalog

import android.app.Application
import im.crisp.catalog.logs.CatalogLogger
import im.crisp.sdk.api.Crisp

class CatalogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registered first, so that no Crisp log is missed
        Crisp.addLogger(CatalogLogger())
        // Started up ahead of time, so that the first chatbox shows faster,
        // without the UI thread tasks not to delay the app start (run once drawn, see MainComposeActivity)
        Crisp.preheat(this, shouldRunUiThreadTasks = false)
    }
}
