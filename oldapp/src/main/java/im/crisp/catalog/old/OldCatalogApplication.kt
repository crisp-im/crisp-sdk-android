package im.crisp.catalog.old

import android.app.Application
import android.util.Log
import im.crisp.client.external.Crisp
import im.crisp.client.external.EventsCallback
import im.crisp.client.external.Logger
import im.crisp.client.external.data.message.Message

class OldCatalogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(LOG_TAG, "Running Crisp SDK v${Crisp.getSDKVersion()}")
        Crisp.setLogLevel(Logger.Level.DEBUG)
        Crisp.addCallback(object : EventsCallback {
            override fun onSessionLoaded(sessionId: String) {
                Log.d(LOG_TAG, "onSessionLoaded: $sessionId")
            }

            override fun onChatOpened() {
                Log.d(LOG_TAG, "onChatOpened")
            }

            override fun onChatClosed() {
                Log.d(LOG_TAG, "onChatClosed")
            }

            override fun onMessageSent(message: Message) {
                Log.d(LOG_TAG, "onMessageSent: ${message.toJSON()}")
            }

            override fun onMessageReceived(message: Message) {
                Log.d(LOG_TAG, "onMessageReceived: ${message.toJSON()}")
            }

            override fun onNotificationReceived(data: MutableMap<String, String>) {
                Log.d(LOG_TAG, "onNotificationReceived: $data")
            }
        })
        // Configured again on each launch, without (re)setting the token ID, kept by the SDK
        ChatboxPreferences.getWebsiteId(this)?.let { Crisp.configure(this, it) }
    }

    companion object {
        const val LOG_TAG = "OldCatalog"
    }
}
