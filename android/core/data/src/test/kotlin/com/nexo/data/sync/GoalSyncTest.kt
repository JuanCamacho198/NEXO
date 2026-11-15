package com.nexo.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2 (pure rule): last-write-wins reconciliation of the local and remote daily
 * goal. No Android, no network — the whole conflict policy is pinned here.
 */
class GoalSyncTest {
    private val older = GoalValue(minutes = 10, updatedAt = 1_000L)
    private val newer = GoalValue(minutes = 30, updatedAt = 2_000L)

    @Test
    fun `both absent resolves to Absent`() {
        assertEquals(GoalReconciliation.Absent, GoalSync.reconcile(local = null, remote = null))
    }

    @Test
    fun `only remote present is adopted`() {
        // Fresh install / wiped data: the account's goal exists only on the
        // server and must come down.
        assertEquals(
            GoalReconciliation.AdoptRemote(newer),
            GoalSync.reconcile(local = null, remote = newer),
        )
    }

    @Test
    fun `only local present is pushed`() {
        // First ever sync of a device that already had a goal: it must go up.
        assertEquals(
            GoalReconciliation.PushLocal(older),
            GoalSync.reconcile(local = older, remote = null),
        )
    }

    @Test
    fun `newer local beats older remote`() {
        // Device A edited offline, its stamp is newer: A wins and pushes.
        assertEquals(
            GoalReconciliation.PushLocal(newer),
            GoalSync.reconcile(local = newer, remote = older),
        )
    }

    @Test
    fun `newer remote beats older local`() {
        // Device B edited more recently: B wins and the local cache hydrates.
        assertEquals(
            GoalReconciliation.AdoptRemote(newer),
            GoalSync.reconcile(local = older, remote = newer),
        )
    }

    @Test
    fun `equal stamps break toward local`() {
        // Same instant on both sides: keep the value the user is looking at.
        assertEquals(
            GoalReconciliation.PushLocal(older),
            GoalSync.reconcile(local = older, remote = older.copy(minutes = 45)),
        )
    }

    @Test
    fun `identical value and clock needs no work`() {
        // Both sides already agree exactly: nothing to push or hydrate.
        assertEquals(
            GoalReconciliation.Keep(older),
            GoalSync.reconcile(local = older, remote = older),
        )
    }

    @Test
    fun `invalid local minutes are treated as absent`() {
        assertEquals(
            GoalReconciliation.AdoptRemote(newer),
            GoalSync.reconcile(local = GoalValue(0, 9_999L), remote = newer),
        )
    }

    @Test
    fun `invalid remote minutes are treated as absent`() {
        assertEquals(
            GoalReconciliation.PushLocal(older),
            GoalSync.reconcile(local = older, remote = GoalValue(-5, 9_999L)),
        )
    }

    @Test
    fun `both invalid resolves to Absent`() {
        assertEquals(
            GoalReconciliation.Absent,
            GoalSync.reconcile(local = GoalValue(0, 1L), remote = GoalValue(-1, 2L)),
        )
    }
}
