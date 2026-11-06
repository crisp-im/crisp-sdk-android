package im.crisp.sdk.internal.chatbox

import android.content.SharedPreferences

/** In-memory [SharedPreferences], as Android ones are not available in unit tests. Booleans and strings only. */
internal class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableListOf<(MutableMap<String, Any?>) -> Unit>()

        override fun putBoolean(key: String, value: Boolean) = apply { changes += { it[key] = value } }
        override fun remove(key: String) = apply { changes += { it.remove(key) } }
        override fun clear() = apply { changes += { it.clear() } }
        override fun putString(key: String, value: String?) = apply { changes += { it[key] = value } }
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun commit() = true.also { apply() }
        override fun apply() = changes.forEach { it(values) }
    }
}
