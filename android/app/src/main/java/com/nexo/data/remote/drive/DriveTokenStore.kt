package com.nexo.data.remote.drive

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nexo.data.remote.google.GoogleDriveConfig
import com.nexo.data.session.AccountPreferences
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.session.ActiveUserProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A pair of OAuth tokens issued by Google for Drive access.
 *
 * @property accessToken Short-lived bearer token used for Drive REST calls.
 * @property refreshToken Long-lived token used to re-issue [accessToken] after expiry.
 */
data class DriveTokenPair(
    val accessToken: String,
    val refreshToken: String?,
)

/**
 * Network boundary for the Google OAuth token endpoint.
 *
 * Kept as an interface so unit tests can substitute a mock token endpoint
 * (see the testing strategy: "Auth: exchange persists pair; refresh reissues").
 */
interface DriveTokenApi {
    /**
     * Exchanges an OAuth authorization code for an access (and refresh) token.
     */
    suspend fun exchange(
        clientId: String,
        authCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): Result<DriveTokenPair>

    /**
     * Re-issues an access token from a refresh token.
     */
    suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): Result<DriveTokenPair>
}

/**
 * Production [DriveTokenApi] implementation using the Ktor HTTP client against
 * Google's [OAuth token endpoint](https://oauth2.googleapis.com/token).
 */
class KtorAuthApi(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DriveTokenApi {
    override suspend fun exchange(
        clientId: String,
        authCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): Result<DriveTokenPair> =
        postToken(
            params =
                buildMap {
                    put("client_id", clientId)
                    put("code", authCode)
                    put("grant_type", "authorization_code")
                    if (redirectUri != null) put("redirect_uri", redirectUri)
                    if (codeVerifier != null) put("code_verifier", codeVerifier)
                },
        )

    override suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): Result<DriveTokenPair> =
        postToken(
            params =
                mapOf(
                    "client_id" to clientId,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
        )

    private suspend fun postToken(params: Map<String, String>): Result<DriveTokenPair> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response =
                    client.post(GoogleDriveConfig.GOOGLE_OAUTH_TOKEN_ENDPOINT) {
                        contentType(io.ktor.http.ContentType.Application.FormUrlEncoded)
                        setBody(FormDataContent(Parameters.build { params.forEach { (k, v) -> append(k, v) } }))
                    }
                val body: TokenResponse = json.decodeFromString(response.body())
                if (response.status.isSuccess() && !body.accessToken.isNullOrBlank()) {
                    DriveTokenPair(
                        accessToken = requireNotNull(body.accessToken) { "Google Drive token response missing accessToken" },
                        refreshToken = body.refreshToken,
                    )
                } else {
                    throw AppError(
                        category = ErrorCategory.AUTH,
                        code = "DRIVE_TOKEN_EXCHANGE_FAILED",
                        message = "Token exchange failed: ${body.error ?: "unknown error"}",
                        component = COMPONENT,
                    )
                }
            }.mapError()
        }

    private fun <T> Result<T>.mapError(): Result<T> = this

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        val scope: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    companion object {
        const val COMPONENT = "DriveTokenStore"
    }
}

/**
 * Encrypted, persisted store for Google Drive OAuth tokens.
 *
 * Tokens survive process restarts so the app can refresh the access token on
 * startup without re-prompting the user. Persistence uses EncryptedSharedPreferences.
 */
interface DriveTokenStore {
    /** The currently stored access token, or null when unauthenticated. */
    fun accessToken(): String?

    /** The currently stored refresh token, or null when none is available. */
    fun refreshToken(): String?

    /** True when an access token is present (Drive is authorized). */
    fun isAuthorized(): Boolean

    /** Persist a freshly issued token pair. */
    fun persist(pair: DriveTokenPair)

    /** Remove all stored Drive tokens (disconnect). */
    fun clear()
}

/**
 * [DriveTokenStore] backed by EncryptedSharedPreferences, scoped to the active
 * account (T10 of `aislamiento-datos-por-cuenta`).
 *
 * ## The security defect this closes
 *
 * The file was one global `nexo_drive_token_store`. Two accounts on the same
 * device therefore shared one token pair, so account B silently inherited
 * account A's Google Drive access. The file is now
 * `nexo_drive_token_store__{userId}`: tokens are encrypted exactly as before
 * (Keystore-backed MasterKey, AES-256 SIV/GCM — unchanged) but stored per owner,
 * so B reads nothing of A's.
 *
 * ## Construction still touches the Keystore
 *
 * The [MasterKey] is built in the CONSTRUCTOR, not on first token access. The
 * `NetworkModule` wiring does `runCatching { EncryptedDriveTokenStore(...) }` and
 * falls back to [InMemoryDriveTokenStore] when the Keystore entry cannot be
 * built; building the key lazily would move that failure to the first Drive call,
 * where no fallback exists.
 *
 * All scoping behavior lives in [PerAccountDriveTokenStore]; this class only
 * supplies the encrypted per-account file. It is the production entry point.
 */
class EncryptedDriveTokenStore(
    context: Context,
    activeUser: ActiveUserProvider,
) : DriveTokenStore by PerAccountDriveTokenStore(
        activeUser = activeUser,
        openForOwner = encryptedFileOpener(context.applicationContext),
    ) {
    companion object {
        /** Base name; the owner segment is appended per account. */
        const val PREFS_NAME: String = "nexo_drive_token_store"

        /**
         * Builds the per-owner encrypted file. The [MasterKey] is created eagerly
         * here so a Keystore failure surfaces during store construction, where the
         * caller's fallback catches it.
         */
        private fun encryptedFileOpener(appContext: Context): (String) -> SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return { owner ->
                EncryptedSharedPreferences.create(
                    appContext,
                    requireNotNull(AccountPreferences.fileNameForOwner(PREFS_NAME, owner)) {
                        "No owner segment for Drive token file"
                    },
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
        }
    }
}

/**
 * [DriveTokenStore] over an injected per-owner [SharedPreferences] resolver.
 *
 * Owns the account-scoping rules (read at call time, per-owner file cache, the
 * anonymous path storing nothing). Production composes it with the encrypted
 * resolver inside [EncryptedDriveTokenStore]; the injectable resolver is what
 * makes the isolation rule provable on the JVM, where the Android Keystore is
 * unavailable — the encryption itself is Android's, and is not what this class
 * is about.
 */
class PerAccountDriveTokenStore(
    private val activeUser: ActiveUserProvider,
    private val openForOwner: (String) -> SharedPreferences,
) : DriveTokenStore {
    /** Cached per owner id; a single container instance serves every account. */
    private val prefsCache = mutableMapOf<String, SharedPreferences>()

    /** The active account's file, or `null` when no account is active (D5). */
    private fun prefs(): SharedPreferences? {
        val owner = activeUser.currentUserId() ?: return null
        return prefsCache.getOrPut(owner) { openForOwner(owner) }
    }

    override fun accessToken(): String? = prefs()?.getString(KEY_ACCESS, null)

    override fun refreshToken(): String? = prefs()?.getString(KEY_REFRESH, null)

    override fun isAuthorized(): Boolean = !accessToken().isNullOrBlank()

    override fun persist(pair: DriveTokenPair) {
        val prefs = prefs() ?: return
        val editor = prefs.edit().putString(KEY_ACCESS, pair.accessToken)
        if (pair.refreshToken != null) {
            editor.putString(KEY_REFRESH, pair.refreshToken)
        }
        editor.apply()
    }

    override fun clear() {
        prefs()?.edit()?.clear()?.apply()
    }

    private companion object {
        const val KEY_ACCESS = "drive_access_token"
        const val KEY_REFRESH = "drive_refresh_token"
    }
}
