package com.nexo.testutil

import android.content.Context
import io.mockk.every
import io.mockk.mockk

/**
 * A mock [Context] for holders that read string resources. `getString(id)`
 * returns a readable marker so a failure message names the resource.
 *
 * Stays in this module rather than in :core:test because it returns an Android
 * type and :core:test is a Kotlin/JVM module every module depends on. :app's
 * testutil carries its own copy for the same reason.
 */
fun mockAppContext(): Context =
    mockk<Context>(relaxed = true).also { context ->
        every { context.getString(any()) } returns "res"
    }
