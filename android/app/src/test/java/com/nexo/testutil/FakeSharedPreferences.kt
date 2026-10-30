package com.nexo.testutil

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for JVM tests.
 *
 * Stands in for the Keystore-backed [androidx.security.crypto.EncryptedSharedPreferences]
 * (same interface; the encryption itself is Android's), so wiring that is built
 * on top of a preferences file — account scoping, JSON blobs, token round-trips
 * — can be exercised without a device.
 */
class FakeSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = data.toMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = if (key != null && data.containsKey(key)) data[key] as String? else defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? =
        if (key != null && data.containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            data[key] as MutableSet<String>?
        } else {
            defValues
        }

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = if (key != null && data.containsKey(key)) data[key] as Int else defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = if (key != null && data.containsKey(key)) data[key] as Long else defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = if (key != null && data.containsKey(key)) data[key] as Float else defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = if (key != null && data.containsKey(key)) data[key] as Boolean else defValue

    override fun contains(key: String?): Boolean = key != null && data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = apply { pending[key as String] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { pending[key as String] = values }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = apply { pending[key as String] = value }

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = apply { pending[key as String] = value }

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = apply { pending[key as String] = value }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = apply { pending[key as String] = value }

        override fun remove(key: String?): SharedPreferences.Editor = apply { removals.add(key as String) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}
