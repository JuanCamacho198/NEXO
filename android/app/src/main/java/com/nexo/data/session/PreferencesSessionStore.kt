package com.nexo.data.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nexo.domain.model.AuthSession

/**
 * Session store backed by EncryptedSharedPreferences (AES-256 GCM).
 *
 * Uses Android Keystore-backed MasterKey to encrypt both keys and values.
 * Data at rest is not readable even with filesystem access.
 */
class PreferencesSessionStore(
    context: Context,
) : SessionStore {
    private val masterKey =
        MasterKey
            .Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val preferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override fun read(): AuthSession? {
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        val email = preferences.getString(KEY_EMAIL, null)
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)
        val photoUrl = preferences.getString(KEY_PHOTO_URL, null)
        val provider = preferences.getString(KEY_PROVIDER, null)
        val createdAt = preferences.getString(KEY_CREATED_AT, null)
        return AuthSession(
            userId = userId,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            provider = provider,
            createdAt = createdAt,
        )
    }

    override fun write(session: AuthSession) {
        preferences
            .edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putString(KEY_PHOTO_URL, session.photoUrl)
            .putString(KEY_PROVIDER, session.provider)
            .putString(KEY_CREATED_AT, session.createdAt)
            .apply()
    }

    override fun clear() {
        preferences
            .edit()
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_PHOTO_URL)
            .remove(KEY_PROVIDER)
            .remove(KEY_CREATED_AT)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "nexo_auth_session"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_PHOTO_URL = "photo_url"
        const val KEY_PROVIDER = "provider"
        const val KEY_CREATED_AT = "created_at"
    }
}
