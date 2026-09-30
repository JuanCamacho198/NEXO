package com.nexo.testutil

import android.app.Application
import io.mockk.mockk

/**
 * Provides a mock Application for AndroidViewModel tests.
 */
fun mockApplication(): Application = mockk(relaxed = true)
