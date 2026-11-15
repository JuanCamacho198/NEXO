package com.nexo.testutil

/**
 * Account fixtures for the reader module's tests.
 *
 * Mirrors the `TEST_USER_ID` / `activeUserProviderFor` pair in :app's
 * testutil so a test that moves into this module keeps compiling. Kept
 * deliberately narrow: :app owns the full fake DAO suite, and this module must
 * not depend on :app (that would be a cycle).
 */
import android.content.Context
import io.mockk.every
import io.mockk.mockk

const val TEST_USER_ID: String = "test-user"

/**
 * A mock [Context] for holders that read string resources. `getString(id)`
 * returns a readable marker so a failure message names the resource.
 */
fun mockAppContext(): Context =
    mockk<Context>(relaxed = true).also { context ->
        every { context.getString(any()) } returns "res"
    }

fun activeUserProviderFor(userId: String? = TEST_USER_ID): com.nexo.domain.session.ActiveUserProvider =
    com.nexo.domain.session
        .ActiveUserProvider()
        .apply { set(userId) }
