package com.nexo.data.session

import android.content.SharedPreferences
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.5 secrets encryption: the supabase-kt token pair round-trips through the encrypted store.
 *
 * The [FakeSharedPreferences] stands in for EncryptedSharedPreferences (same interface; the Keystore-backed
 * encryption itself is Android's, already exercised by the Drive token store). What this proves is the wiring that
 * is actually new: [EncryptedSessionSettings] bridges supabase-kt's [SettingsSessionManager], migrates the legacy
 * plaintext entry once, and a real [UserSession] survives save/load/delete through it.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class EncryptedSupabaseSessionTest {
    private fun sessionKey(): String = EncryptedSessionSettings.defaultSessionKey("https://xyz.supabase.co")

    private fun storedSession(): UserSession =
        UserSession(
            accessToken = "access-123",
            refreshToken = "refresh-123",
            expiresIn = 3600,
            tokenType = "bearer",
        )

    @Test
    fun settingsBridge_roundTripsStrings() {
        val settings = EncryptedSessionSettings(FakeSharedPreferences(), sessionKey(), null)

        settings.putString("k", "v")

        assertEquals("v", settings.getStringOrNull("k"))
        assertTrue(settings.hasKey("k"))
    }

    @Test
    fun legacyPlaintextSession_migratesOnceAndLeavesNoBackup() {
        val key = sessionKey()
        val delegate = FakeSharedPreferences()
        val legacy = FakeSharedPreferences()
        legacy.data[key] = "legacy-session-json"

        EncryptedSessionSettings(delegate, key, legacy)

        assertEquals("legacy-session-json", delegate.getString(key, null))
        assertFalse(legacy.contains(key))
    }

    @Test
    fun existingEncryptedValue_isNeverOverwrittenByLegacy() {
        val key = sessionKey()
        val delegate = FakeSharedPreferences()
        delegate.data[key] = "encrypted-current"
        val legacy = FakeSharedPreferences()
        legacy.data[key] = "legacy-stale"

        EncryptedSessionSettings(delegate, key, legacy)

        assertEquals("encrypted-current", delegate.getString(key, null))
        assertFalse(legacy.contains(key))
    }

    @Test
    fun supabaseSession_roundTripsThroughEncryptedBridge() =
        runTest {
            val manager = SettingsSessionManager(EncryptedSessionSettings(FakeSharedPreferences(), sessionKey(), null))
            val session = storedSession()

            manager.saveSession(session)

            assertEquals(session, manager.loadSession())
        }

    @Test
    fun supabaseSession_deleteClearsEncryptedBridge() =
        runTest {
            val manager = SettingsSessionManager(EncryptedSessionSettings(FakeSharedPreferences(), sessionKey(), null))

            manager.saveSession(storedSession())
            manager.deleteSession()

            assertNull(manager.loadSession())
        }

    @Test
    fun defaultSessionKey_matchesSupabaseDerivation() {
        assertEquals(
            "sb-https:--xyz-supabase-co-session",
            EncryptedSessionSettings.defaultSessionKey("https://xyz.supabase.co"),
        )
        assertEquals(
            "sb-https:--xyz-supabase-co-session",
            EncryptedSessionSettings.defaultSessionKey("https://xyz.supabase.co/"),
        )
    }

    private class FakeSharedPreferences : SharedPreferences {
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
}
