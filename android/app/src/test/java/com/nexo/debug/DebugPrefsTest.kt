package com.nexo.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugPrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `defaults to OFF on a fresh install`() {
        assertFalse(DebugPrefs.isEnabled(context))
    }

    @Test
    fun `enabled state persists across restarts`() {
        DebugPrefs.setEnabled(context, true)

        // A "restart" is a new read from the same persisted prefs.
        assertTrue(DebugPrefs.isEnabled(context))

        DebugPrefs.setEnabled(context, false)
        assertFalse(DebugPrefs.isEnabled(context))
    }

    @Test
    fun `toggle is the sole authority independent of any user id`() {
        // No user-id surface exists on DebugPrefs; enabling it alone must be
        // enough and an unknown/non-local user must not change the result.
        DebugPrefs.setEnabled(context, true)
        assertTrue(DebugPrefs.isEnabled(context))
    }

    private companion object {
        const val PREFS_NAME = "nexo_debug"
    }
}
