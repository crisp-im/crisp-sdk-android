package im.crisp.catalog

import android.app.Application
import android.util.Log
import im.crisp.catalog.logs.CatalogLogger
import im.crisp.sdk.api.Crisp

class CatalogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Crisp.setLogLevel(Log.VERBOSE)
        // Registered first, so that no Crisp log is missed
        Crisp.addLogger(CatalogLogger())
        // Started up ahead of time, so that the first chatbox shows faster,
        // without the UI thread tasks not to delay the app start (run once drawn, see MainComposeActivity)
        Crisp.preheat(this, shouldRunUiThreadTasks = false)
        // Notifications
        //CrispNotifications.setEnabled(this, "YOUR_WEBSITE_ID", true)
    }
}
