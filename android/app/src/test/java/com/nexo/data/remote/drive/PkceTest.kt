package com.nexo.data.remote.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [Pkce] (RFC 7636 verifier/challenge contract). */
class PkceTest {
    @Test
    fun generateVerifier_is43CharsBase64Url() {
        val verifier = Pkce.generateVerifier()
        assertEquals("verifier must be exactly 43 chars", 43, verifier.length)
        assertTrue("verifier charset must be base64url", verifier.all { it in BASE64URL_CHARS })
        assertTrue("verifier must have no padding", !verifier.contains("="))
    }

    @Test
    fun generateVerifier_isRandomAcrossCalls() {
        assertNotEquals(Pkce.generateVerifier(), Pkce.generateVerifier())
    }

    @Test
    fun challenge_matchesRfc7636AppendixBVector() {
        // Official RFC 7636 §Appendix B test pair.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, Pkce.challenge(verifier))
    }

    @Test
    fun challenge_isDeterministicForSameVerifier() {
        val verifier = Pkce.generateVerifier()
        assertEquals(Pkce.challenge(verifier), Pkce.challenge(verifier))
    }

    @Test
    fun challenge_mismatch_forDifferentVerifiers() {
        assertNotEquals(Pkce.challenge("verifier-a"), Pkce.challenge("verifier-b"))
    }

    private companion object {
        val BASE64URL_CHARS: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + '-' + '_').toSet()
    }
}
