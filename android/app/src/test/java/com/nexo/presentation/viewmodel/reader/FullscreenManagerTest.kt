package com.nexo.presentation.viewmodel.reader

import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FullscreenManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `default state is not fullscreen`() {
        val manager = FullscreenManager()

        val state = manager.state.value
        assertFalse("Default should not be fullscreen", state.isFullscreen)
    }

    @Test
    fun `onToggleFullscreen flips to true`() {
        val manager = FullscreenManager()

        manager.onToggleFullscreen()

        assertTrue("Should be fullscreen after toggle", manager.state.value.isFullscreen)
    }

    @Test
    fun `onToggleFullscreen flips back to false`() {
        val manager = FullscreenManager()

        manager.onToggleFullscreen()
        assertTrue("Should be fullscreen after first toggle", manager.state.value.isFullscreen)

        manager.onToggleFullscreen()

        assertFalse("Should not be fullscreen after second toggle", manager.state.value.isFullscreen)
    }

    @Test
    fun `enterFullscreen sets true idempotently`() {
        val manager = FullscreenManager()

        manager.enterFullscreen()
        assertTrue("Should be fullscreen after enter", manager.state.value.isFullscreen)

        // Calling again must not flip back to false.
        manager.enterFullscreen()
        assertTrue("Should remain fullscreen after repeated enter", manager.state.value.isFullscreen)
    }

    @Test
    fun `enterFullscreen from default differs from reset`() {
        val enterManager = FullscreenManager()
        enterManager.enterFullscreen()
        assertTrue("enter should be fullscreen", enterManager.state.value.isFullscreen)

        val resetManager = FullscreenManager()
        resetManager.reset()
        assertFalse("reset should not be fullscreen", resetManager.state.value.isFullscreen)
    }

    @Test
    fun `reset brings back to default`() {
        val manager = FullscreenManager()

        manager.onToggleFullscreen()
        assertTrue("Should be fullscreen after toggle", manager.state.value.isFullscreen)

        manager.reset()

        val state = manager.state.value
        assertFalse("Should not be fullscreen after reset", state.isFullscreen)
        assertEquals("Default state should match", FullscreenState(), state)
    }
}
