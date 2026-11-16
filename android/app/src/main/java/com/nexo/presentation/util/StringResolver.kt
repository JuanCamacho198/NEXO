package com.nexo.presentation.util

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Resolves a string resource id to displayable text.
 *
 * Presentation-layer state holders take this instead of a [Context] so they can
 * emit user-facing copy without depending on Android. Production resolves
 * through [Context.getString]; tests pass a plain map-backed fake and therefore
 * need no Robolectric.
 *
 * [args] carry positional format arguments for keys that declare them, mirroring
 * the `vararg` form of [Context.getString].
 */
fun interface StringResolver {
    fun get(
        @StringRes id: Int,
        vararg args: Any,
    ): String
}

/**
 * Production [StringResolver] backed by the application context.
 *
 * Bound in `HiltSingletonsModule`; a `fun interface` cannot be bound by
 * `@Inject` on its implementation alone.
 */
class AndroidStringResolver(
    @ApplicationContext private val context: Context,
) : StringResolver {
    override fun get(
        @StringRes id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)
}
