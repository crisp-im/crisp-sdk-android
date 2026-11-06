package im.crisp.sdk.internal.chatbox

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import im.crisp.sdk.api.CrispConfig

/**
 * The sessions the Web client bound to a tokenId.
 * Persisted, as the session is kept in the WebView storage,
 * shared by all the chatboxes of the app and across launches.
 *
 * Loaded without a tokenId, the Web client restores that session, which must then be reset.
 */
internal class CrispSessions(private val preferences: SharedPreferences) {
    /**
     * Records that [config][CrispConfig] is loaded,
     * returning whether its session must be reset once the page is ready.
     */
    fun onLoad(config: CrispConfig): Boolean {
        val key = key(config.websiteId)
        return if (config.tokenId != null) {
            preferences.edit { putBoolean(key, true) }
            false
        } else {
            preferences.getBoolean(key, false)
        }
    }

    /**
     * Records that the session of [websiteId] was reset, so it is no longer bound to a tokenId.
     */
    fun onSessionReset(websiteId: String) {
        preferences.edit { remove(key(websiteId)) }
    }

    companion object {
        private const val NAME = "im.crisp.sdk.sessions"

        fun from(context: Context) =
            CrispSessions(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE))

        private fun key(websiteId: String) = "session.$websiteId"
    }
}
