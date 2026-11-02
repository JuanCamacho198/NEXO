package com.nexo.testutil

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk

/**
 * Provides a mock Application for AndroidViewModel tests.
 */
fun mockApplication(): Application = mockk(relaxed = true)

/**
 * Provides a mock [Context] for holders that read string resources.
 *
 * `getString(id)` returns a readable marker so a failure message names the
 * resource instead of showing a blank. Tests that assert on the real
 * user-facing copy belong under Robolectric with
 * `ApplicationProvider.getApplicationContext()` instead.
 */
fun mockAppContext(): Context =
    mockk<Context>(relaxed = true).also { context ->
        every { context.getString(any()) } returns "res"
    }
