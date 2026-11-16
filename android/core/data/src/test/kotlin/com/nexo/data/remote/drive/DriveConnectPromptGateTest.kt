package com.nexo.data.remote.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DriveConnectPromptGate] — the one-time connect prompt decision
 * (spec `drive-import-connect`). The gate is pure, so the decline persistence
 * round-trip is simulated by feeding the `markDeclined` result back into
 * `declinedForUser` on the next `shouldShow` call.
 */
class DriveConnectPromptGateTest {
    private val gate = DriveConnectPromptGate

    private val userId = "user-google-1"

    // ── Spec scenario: signed-in, Drive-disconnected, two imports → exactly once ──

    @Test
    fun twoImports_showsExactlyOnce_whenDeclinePersisted() {
        // First import: never declined → prompt shows.
        assertTrue(
            "first import should offer the prompt",
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = false,
                providerIsGoogle = true,
                declinedForUser = null,
                currentUser = userId,
            ),
        )

        // User declines → marker persisted per account.
        val declinedForUser = gate.markDeclined(userId)

        // Second import: same account already declined → prompt must not reappear.
        assertFalse(
            "second import must not re-offer a declined account",
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = false,
                providerIsGoogle = true,
                declinedForUser = declinedForUser,
                currentUser = userId,
            ),
        )
    }

    // ── Spec scenario: Drive already authorized → never ──

    @Test
    fun driveEnabled_neverShows() {
        assertFalse(
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = true,
                providerIsGoogle = true,
                declinedForUser = null,
                currentUser = userId,
            ),
        )
    }

    // ── Spec scenario: import failure → never ──

    @Test
    fun importFailed_neverShows() {
        assertFalse(
            gate.shouldShow(
                importSucceeded = false,
                driveEnabled = false,
                providerIsGoogle = true,
                declinedForUser = null,
                currentUser = userId,
            ),
        )
    }

    // ── Spec scenario: no Google sign-in → never ──

    @Test
    fun nonGoogleProvider_neverShows() {
        assertFalse(
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = false,
                providerIsGoogle = false,
                declinedForUser = null,
                currentUser = userId,
            ),
        )
    }

    @Test
    fun noSignedInUser_neverShows() {
        assertFalse(
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = false,
                providerIsGoogle = true,
                declinedForUser = null,
                currentUser = null,
            ),
        )
    }

    // ── Spec scenario: decline is per account ──

    @Test
    fun declineIsPerAccount_otherAccountStillOffered() {
        val declinedForUser = gate.markDeclined("user-A")
        assertTrue(
            "a different account must still be offered",
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = false,
                providerIsGoogle = true,
                declinedForUser = declinedForUser,
                currentUser = "user-B",
            ),
        )
    }

    // ── Spec scenario: MAY re-offer after Drive is disabled in Settings ──

    @Test
    fun reOfferAllowed_afterSettingsDisable_clearsDecline() {
        val declinedForUser = gate.markDeclined(userId)

        // While Drive is (re)authorized the prompt is suppressed regardless of decline.
        assertFalse(
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = true,
                providerIsGoogle = true,
                declinedForUser = declinedForUser,
                currentUser = userId,
            ),
        )

        // Success authorization clears the decline (see NavHost wiring); once Drive
        // is disabled again in Settings a later import MAY re-offer.
        assertTrue(
            gate.shouldShow(
                importSucceeded = true,
                driveEnabled = false,
                providerIsGoogle = true,
                declinedForUser = null,
                currentUser = userId,
            ),
        )
    }

    // ── markDeclined contract ──

    @Test
    fun markDeclined_returnsCurrentUserMarker() {
        assertEquals(userId, gate.markDeclined(userId))
    }

    @Test
    fun markDeclined_blankOrNullUser_returnsNull() {
        assertNull(gate.markDeclined(null))
        assertNull(gate.markDeclined(""))
        assertNull(gate.markDeclined("   "))
    }
}
