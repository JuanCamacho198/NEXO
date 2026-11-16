package com.nexo.testutil

import com.nexo.presentation.util.StringResolver

/**
 * Map-backed [StringResolver] for JVM tests.
 *
 * Missing keys resolve to [fallback] rather than throwing, so a test that only
 * cares about the happy path does not have to enumerate every resource. Tests
 * that assert on copy must put the exact expected text in the map first.
 */
class FakeStringResolver(
    private val strings: Map<Int, String> = emptyMap(),
    private val fallback: String = "",
) : StringResolver {
    override fun get(
        id: Int,
        vararg args: Any,
    ): String {
        val template = strings[id] ?: return fallback
        return if (args.isEmpty()) template else format(template, args)
    }

    private fun format(
        template: String,
        args: Array<out Any>,
    ): String {
        var index = 0
        return POSITIONAL.replace(template) { _ ->
            args.getOrNull(index++)?.toString() ?: ""
        }
    }

    private companion object {
        private val POSITIONAL = Regex("%\\d+\\\$s|%s")
    }
}
