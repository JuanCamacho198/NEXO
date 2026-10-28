package com.nexo.presentation.navigation

import com.nexo.data.session.ReadingGoalPreferences

/**
 * The start-destination gate (T11 of `aislamiento-datos-por-cuenta`).
 *
 * ## Is a dedicated "onboarding completed" flag needed?
 *
 * No — and one is deliberately NOT added. The goal IS the onboarding outcome:
 * the only onboarding step that can complete is the goal step, and
 * [ReadingGoalPreferences.save] is its only writer
 * (`OnboardingGoalScreen`). "The active account has a goal" therefore already
 * answers "this account finished onboarding" — a second flag would be a second
 * source of truth that can disagree with the first.
 *
 * That inference was wrong before only because the goal was global: a second
 * account found someone else's goal and skipped onboarding. Now the goal is
 * per-account, so a brand-new account resolves `null` and sees onboarding while
 * the first account's goal stays untouched.
 *
 * ## Why this is a named object and not an inline `load() != null`
 *
 * The gate is a behavior worth naming and testing on its own. It stays the whole
 * rule: the account scoping lives in [ReadingGoalPreferences], and the "no
 * account active" case needs no branch here because `load()` already returns
 * `null` with no owner (so a signed-out or anonymous caller is never "onboarded"
 * — decision D5).
 */
object OnboardingGate {
    /**
     * True when the account [readingGoalPreferences] is scoped to already has a
     * daily goal, i.e. it completed onboarding and should start at Home.
     */
    fun hasDailyGoal(readingGoalPreferences: ReadingGoalPreferences): Boolean =
        readingGoalPreferences.load() != null
}
