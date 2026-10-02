package com.nexo.data.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.Settings

/**
 * russhwolf [Settings] backed by EncryptedSharedPreferences (AES-256 GCM).
 *
 * This is the storage behind supabase-kt's [SettingsSessionManager][io.github.jan.supabase.auth.SettingsSessionManager]:
 * the Supabase access/refresh token pair is encrypted at rest with a Keystore-backed MasterKey, using the same
 * EncryptedSharedPreferences pattern as [EncryptedDriveTokenStore][com.nexo.data.remote.drive.EncryptedDriveTokenStore].
 *
 * Threat model: stolen device / disk removed. Keystore-backed encryption satisfies this. Malware running as the app
 * user is explicitly OUT of scope and is not defended against here.
 *
 * First-run migration: when the encrypted store holds no session yet but the legacy plaintext default preferences
 * still carry one (same per-project key supabase-kt used before), the value is copied into the encrypted store and
 * the legacy entry is removed, so NO plaintext backup remains.
 */
class EncryptedSessionSettings internal constructor(
    private val delegate: SharedPreferences,
    private val sessionKey: String,
    migrateFrom: SharedPreferences?,
) : Settings {
    init {
        val legacy = migrateFrom?.getString(sessionKey, null)
        if (legacy != null) {
            if (!delegate.contains(sessionKey)) {
                delegate.edit().putString(sessionKey, legacy).apply()
            }
            migrateFrom.edit().remove(sessionKey).apply()
        }
    }

    override val keys: Set<String> get() = delegate.all.keys

    override val size: Int get() = delegate.all.size

    @SuppressLint("CommitPrefEdits")
    override fun clear() {
        delegate
            .edit()
            .apply {
                for (key in delegate.all.keys) {
                    remove(key)
                }
            }.apply()
    }

    @SuppressLint("CommitPrefEdits")
    override fun remove(key: String) {
        delegate.edit().remove(key).apply()
    }

    override fun hasKey(key: String): Boolean = delegate.contains(key)

    @SuppressLint("CommitPrefEdits")
    override fun putInt(
        key: String,
        value: Int,
    ) {
        delegate.edit().putInt(key, value).apply()
    }

    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = delegate.getInt(key, defaultValue)

    override fun getIntOrNull(key: String): Int? = if (delegate.contains(key)) delegate.getInt(key, 0) else null

    @SuppressLint("CommitPrefEdits")
    override fun putLong(
        key: String,
        value: Long,
    ) {
        delegate.edit().putLong(key, value).apply()
    }

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = delegate.getLong(key, defaultValue)

    override fun getLongOrNull(key: String): Long? = if (delegate.contains(key)) delegate.getLong(key, 0L) else null

    @SuppressLint("CommitPrefEdits")
    override fun putString(
        key: String,
        value: String,
    ) {
        delegate.edit().putString(key, value).apply()
    }

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = delegate.getString(key, defaultValue) ?: defaultValue

    override fun getStringOrNull(key: String): String? = if (delegate.contains(key)) delegate.getString(key, "") else null

    @SuppressLint("CommitPrefEdits")
    override fun putFloat(
        key: String,
        value: Float,
    ) {
        delegate.edit().putFloat(key, value).apply()
    }

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = delegate.getFloat(key, defaultValue)

    override fun getFloatOrNull(key: String): Float? = if (delegate.contains(key)) delegate.getFloat(key, 0f) else null

    @SuppressLint("CommitPrefEdits")
    override fun putDouble(
        key: String,
        value: Double,
    ) {
        delegate.edit().putLong(key, value.toRawBits()).apply()
    }

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ): Double = Double.fromBits(delegate.getLong(key, defaultValue.toRawBits()))

    override fun getDoubleOrNull(key: String): Double? = if (delegate.contains(key)) Double.fromBits(delegate.getLong(key, 0.0.toRawBits())) else null

    @SuppressLint("CommitPrefEdits")
    override fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        delegate.edit().putBoolean(key, value).apply()
    }

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = delegate.getBoolean(key, defaultValue)

    override fun getBooleanOrNull(key: String): Boolean? = if (delegate.contains(key)) delegate.getBoolean(key, false) else null

    companion object {
        const val PREFS_NAME = "nexo_supabase_session"

        /**
         * Builds the store against the real EncryptedSharedPreferences. Throws on Keystore failure; callers keep the
         * historical in-memory fallback for that case (mirrors the Drive token store wiring).
         */
        fun create(
            context: Context,
            supabaseUrl: String,
        ): EncryptedSessionSettings {
            val appContext = context.applicationContext
            val masterKey =
                MasterKey
                    .Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val encrypted =
                EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            val legacy =
                appContext.getSharedPreferences(
                    "${appContext.packageName}_preferences",
                    Context.MODE_PRIVATE,
                )
            return EncryptedSessionSettings(encrypted, defaultSessionKey(supabaseUrl), legacy)
        }

        /**
         * The per-project session key supabase-kt's default manager uses. Replicates its internal derivation
         * (`sb-<url>-session`) so first-run migration finds the legacy plaintext entry. If a future supabase-kt
         * release changes that derivation, migration silently finds nothing and the user signs in again once.
         */
        fun defaultSessionKey(supabaseUrl: String): String {
            val normalized = supabaseUrl.removeSuffix("/").replace('/', '-').replace('.', '-')
            return "sb-$normalized-session"
        }
    }
}
