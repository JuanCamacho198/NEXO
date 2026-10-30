package com.nexo.presentation.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The onboarding flash is a decision made too early, so the gate is expressed as
 * a pure function of "is the account known?" and pinned here.
 *
 * Three inputs, three answers: signed out goes to Auth; authenticated with a
 * *known* account resolves to onboarding or Home by that account's goal; and
 * authenticated with the account still unresolved holds the start destination at
 * `null` so the host shows its spinner instead of guessing.
 *
 * The unresolved case is the one that matters. Reading it as "no goal" is what
 * flashed the goal step for a split second on every returning user's launch,
 * before `SessionSyncEffect` published the id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStartDestinationGateTest {
    @Test
    fun signedOutLandsOnAuthWhateverTheGoalSays() =
        runTest {
            assertEquals(
                NexoDestination.Auth.route,
                resolveStartDestination(isAuthenticated = false, hasDailyGoal = null),
            )
            assertEquals(
                NexoDestination.Auth.route,
                resolveStartDestination(isAuthenticated = false, hasDailyGoal = true),
            )
        }

    @Test
    fun unresolvedAccountHoldsTheDestinationInsteadOfGuessing() =
        runTest {
            // The account is authenticated but the active id has not landed yet.
            // `null` here is what keeps the flash from happening.
            assertNull(
                resolveStartDestination(isAuthenticated = true, hasDailyGoal = null),
            )
        }

    @Test
    fun brandNewAccountSeesOnboarding() =
        runTest {
            assertEquals(
                NexoDestination.OnboardingGoal.route,
                resolveStartDestination(isAuthenticated = true, hasDailyGoal = false),
            )
        }

    @Test
    fun returningAccountGoesStraightHome() =
        runTest {
            assertEquals(
                NexoDestination.Home.route,
                resolveStartDestination(isAuthenticated = true, hasDailyGoal = true),
            )
        }
}
