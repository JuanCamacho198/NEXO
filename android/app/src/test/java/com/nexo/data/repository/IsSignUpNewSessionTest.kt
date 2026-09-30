package com.nexo.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the stale-session sign-up guard [isSignUpNewSession]
 * (REQ-auth-email-register-login-3, SCEN-1/2).
 *
 * The guard answers: "did `signUpWith` genuinely create a NEW account
 * session, or are we still looking at the previous (stale) session?"
 */
class IsSignUpNewSessionTest {
    @Test
    fun returnsFalse_whenNoFreshSession() {
        assertFalse(isSignUpNewSession(priorUserId = null, freshUserId = null))
        assertFalse(isSignUpNewSession(priorUserId = "prior-1", freshUserId = null))
    }

    @Test
    fun returnsTrue_whenNoPriorSession_andFreshSessionExists() {
        // First-ever sign-up (no persisted session to go stale).
        assertTrue(isSignUpNewSession(priorUserId = null, freshUserId = "fresh-1"))
    }

    @Test
    fun returnsFalse_whenFreshSessionEqualsPrior() {
        // Already-registered email with a stale persisted session: signUpWith
        // leaves the OLD session readable → same user id → must NOT navigate
        // that wrong account into Home (SCEN-2).
        assertFalse(isSignUpNewSession(priorUserId = "user-1", freshUserId = "user-1"))
    }

    @Test
    fun returnsTrue_whenFreshSessionDiffersFromPrior() {
        // Genuine new account: fresh session belongs to a different user (SCEN-1).
        assertTrue(isSignUpNewSession(priorUserId = "user-1", freshUserId = "user-2"))
    }
}
