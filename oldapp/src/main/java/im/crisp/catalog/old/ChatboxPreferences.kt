package im.crisp.catalog.old

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The websiteId and tokenId set in pre-3.x SDK, kept across launches.
 *
 * Same preferences as `ChatboxPreferences` of `app`, so that it loads them once installed over this app.
 */
object ChatboxPreferences {
    private const val NAME = "chatbox"
    private const val KEY_WEBSITE_ID = "website_id"
    private const val KEY_TOKEN_ID = "token_id"

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getWebsiteId(context: Context): String? = preferences(context).getString(KEY_WEBSITE_ID, null)

    fun setWebsiteId(context: Context, websiteId: String) {
        preferences(context).edit { putString(KEY_WEBSITE_ID, websiteId) }
    }

    fun getTokenId(context: Context): String? = preferences(context).getString(KEY_TOKEN_ID, null)

    /** Sets the token ID, removed if `null`. */
    fun setTokenId(context: Context, tokenId: String?) {
        preferences(context).edit {
            if (tokenId == null) remove(KEY_TOKEN_ID) else putString(KEY_TOKEN_ID, tokenId)
        }
    }
}
