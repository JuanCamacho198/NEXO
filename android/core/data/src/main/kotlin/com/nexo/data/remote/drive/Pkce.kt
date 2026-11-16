package com.nexo.data.remote.drive

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636) primitives for the Google Drive OAuth authorization-code flow.
 *
 * Google's OAuth endpoints are public clients for Android OAuth client IDs:
 * there is no client_secret, so the token exchange is protected by a per-attempt
 * **code_verifier** whose SHA-256 **code_challenge** is sent in the authorize
 * request. The verifier must never be reused across attempts (see
 * [generateVerifier]) and the challenge is computed with the S256 method.
 *
 * Pure Kotlin — no Android dependency — so the whole class is JVM-unit-testable.
 */
object Pkce {
    /** RFC 7636 requires the verifier to be 43..128 characters; 32 bytes → 43 base64url chars. */
    private const val VERIFIER_BYTE_LENGTH = 32

    private val secureRandom = SecureRandom()

    /**
     * Generate a fresh 43-character code_verifier from 32 secure random bytes,
     * encoded as unpadded base64url ([A-Za-z0-9-_], no `=` padding).
     */
    fun generateVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        return base64Url(bytes)
    }

    /**
     * S256 code_challenge for a [verifier], per RFC 7636 §4.2:
     * `BASE64URL-ENCODE(SHA256(ASCII(verifier)))`.
     *
     * Deterministic: the same verifier always yields the same challenge, which is
     * what lets the token endpoint prove the exchange came from the same client.
     */
    fun challenge(verifier: String): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
}
