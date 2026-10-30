package com.nexo.presentation.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11: the onboarding gate is per account.
 *
 * Proves the criterion "a brand-new account sees onboarding even when another
 * account already set a goal", and the inverse "an account that set its goal
 * does not".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerFor(userId: String?) = ActiveUserProvider().apply { set(userId) }

    @Test
    fun `account without a goal is sent to onboarding`() {
        val goals = ReadingGoalPreferences(context, providerFor(TEST_USER_ID))
        assertFalse(OnboardingGate.hasDailyGoal(goals))
    }

    @Test
    fun `account that set its goal is not sent to onboarding`() {
        ReadingGoalPreferences(context, providerFor(TEST_USER_ID)).save(30)

        val goals = ReadingGoalPreferences(context, providerFor(TEST_USER_ID))
        assertTrue(OnboardingGate.hasDailyGoal(goals))
    }

    @Test
    fun `a second account still sees onboarding after the first set a goal`() {
        ReadingGoalPreferences(context, providerFor(TEST_USER_ID)).save(30)

        // The first account is onboarded...
        assertTrue(OnboardingGate.hasDailyGoal(ReadingGoalPreferences(context, providerFor(TEST_USER_ID))))
        // ...and the second must NOT inherit that answer.
        assertFalse(OnboardingGate.hasDailyGoal(ReadingGoalPreferences(context, providerFor(OTHER_USER_ID))))
    }

    @Test
    fun `no active account is never treated as onboarded`() {
        ReadingGoalPreferences(context, providerFor(TEST_USER_ID)).save(30)

        val goals = ReadingGoalPreferences(context, providerFor(null))
        assertFalse(OnboardingGate.hasDailyGoal(goals))
    }
}
