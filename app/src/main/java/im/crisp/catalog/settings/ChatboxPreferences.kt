package im.crisp.catalog.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import im.crisp.catalog.R
import im.crisp.sdk.api.CrispConfig
import java.util.function.Consumer

/**
 * The [config][CrispConfig] loaded by the chatboxes, set in the chatbox controller form and kept across launches:
 * - the `websiteId`, defaulting to the first of `R.array.crisp_websites_id`, or `R.string.crisp_website_id` if empty.
 * - the `tokenId`, defaulting to `null`.
 *
 * `R.string.crisp_website_id` is also used by XML attributes, which their hosts override with this config.
 */
object ChatboxPreferences {
    private const val NAME = "chatbox"
    private const val KEY_WEBSITE_ID = "website_id"
    private const val KEY_TOKEN_ID = "token_id"

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getWebsiteId(context: Context): String =
        preferences(context).getString(KEY_WEBSITE_ID, null)
            ?: context.resources.getStringArray(R.array.crisp_websites_id).first().orEmpty()
                .ifEmpty { context.getString(R.string.crisp_website_id) }

    @JvmStatic
    fun setWebsiteId(context: Context, websiteId: String) {
        preferences(context).edit { putString(KEY_WEBSITE_ID, websiteId) }
    }

    @JvmStatic
    fun getTokenId(context: Context): String? = preferences(context).getString(KEY_TOKEN_ID, null)

    /** Sets the token ID, removed if `null`. */
    @JvmStatic
    fun setTokenId(context: Context, tokenId: String?) {
        preferences(context).edit {
            if (tokenId == null) remove(KEY_TOKEN_ID) else putString(KEY_TOKEN_ID, tokenId)
        }
    }

    @JvmStatic
    fun getConfig(context: Context): CrispConfig = CrispConfig(getWebsiteId(context), getTokenId(context))

    /** Calls [onChange][Consumer] with each new config, until [owner][LifecycleOwner] is destroyed. */
    @JvmStatic
    fun observeConfig(context: Context, owner: LifecycleOwner, onChange: Consumer<CrispConfig>) {
        // Referenced by the observer, as SharedPreferences only keeps a weak reference to listeners
        val listener = addListener(context) { onChange.accept(getConfig(context)) }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                removeListener(context, listener)
            }
        })
    }

    /** Calls [onChange] when the website ID or the token ID changes. */
    private fun addListener(context: Context, onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_WEBSITE_ID || key == KEY_TOKEN_ID) {
                onChange()
            }
        }.also { preferences(context).registerOnSharedPreferenceChangeListener(it) }

    private fun removeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** The value [read] from the preferences, recomposing when the config changes. */
    @Composable
    internal fun <T> rememberValue(read: (Context) -> T): T {
        val context = LocalContext.current
        var value by remember(context) { mutableStateOf(read(context)) }
        DisposableEffect(context) {
            // Read again, in case it changed before the listener is added
            value = read(context)
            val listener = addListener(context) { value = read(context) }
            onDispose { removeListener(context, listener) }
        }
        return value
    }
}

/** The [config][ChatboxPreferences], recomposing with each new one. */
@Composable
fun rememberChatboxConfig(): CrispConfig = ChatboxPreferences.rememberValue(ChatboxPreferences::getConfig)

/** The [website ID][ChatboxPreferences], recomposing with each new one. */
@Composable
fun rememberWebsiteId(): String = ChatboxPreferences.rememberValue(ChatboxPreferences::getWebsiteId)

/** The [token ID][ChatboxPreferences], recomposing with each new one. */
@Composable
fun rememberTokenId(): String? = ChatboxPreferences.rememberValue(ChatboxPreferences::getTokenId)
