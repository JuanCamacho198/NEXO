package com.nexo.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FIX 3 regression: [PerformanceViewModel] must be constructible both through
 * the explicit [PerformanceViewModel.Factory] and through the framework's
 * reflective [ViewModelProvider.AndroidViewModelFactory].
 *
 * Root cause of NEXTPAGE-ANDROID-1: a defaulted constructor parameter generated
 * a synthetic constructor, so `getConstructor(Application)` failed with
 * `NoSuchMethodException: PerformanceViewModel.<init> [android.app.Application]`.
 * The first test pins the single `(Application)` constructor shape; the other
 * two pin the two construction paths.
 *
 * Uses [StandardTestDispatcher] so the `init { loadAll() }` coroutine is queued
 * but never runs — construction is exercised without touching Android services.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModelConstructionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private fun relaxedApplication(): Application = mockk<Application>(relaxed = true).also { every { it.applicationContext } returns it }

    @Test
    fun `exposes a single Application constructor for AndroidViewModelFactory reflection`() {
        val constructor =
            PerformanceViewModel::class.java
                .getConstructor(Application::class.java)
        assertNotNull("PerformanceViewModel must keep a (Application) constructor", constructor)
        assertEquals(Application::class.java, constructor.parameterTypes.first())
    }

    @Test
    fun `explicit Factory constructs the ViewModel with its Application`() {
        val viewModel =
            PerformanceViewModel
                .Factory(relaxedApplication())
                .create(PerformanceViewModel::class.java)

        assertTrue(viewModel is PerformanceViewModel)
    }

    @Test
    fun `AndroidViewModelFactory can still construct the ViewModel`() {
        val viewModel =
            ViewModelProvider.AndroidViewModelFactory
                .getInstance(relaxedApplication())
                .create(PerformanceViewModel::class.java)

        assertTrue(viewModel is PerformanceViewModel)
    }
}
