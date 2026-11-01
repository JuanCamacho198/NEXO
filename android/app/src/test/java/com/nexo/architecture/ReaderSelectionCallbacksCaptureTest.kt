package com.nexo.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Konsist rule (reader bug 3 — stale captured selection callbacks).
 *
 * `rememberReaderSelectionCallbacks` builds its lambdas inside `remember(viewModel)`.
 * `remember` caches on the given keys, so a lambda that reads the
 * `annotationUiState` *parameter* is frozen to the state of the composition that
 * created it. `onCopy` and `onDelete` did exactly that: when the selection
 * arrived after the first render the captured value stayed `null`, and the
 * delete-highlight button silently did nothing.
 *
 * The correct shape is the one `onColorSelected`/`onAnnotate` already use —
 * read the live slice inside the lambda at invocation time
 * (`viewModel.annotationUiState.value` / `viewModel.sessionUiState.value`).
 *
 * This rule is file-scoped on purpose: it asserts the contract where the
 * callbacks are built, since Compose composition cannot run on the JVM
 * classpath (`compose-ui-test-junit4` is `androidTestImplementation` only).
 */
class ReaderSelectionCallbacksCaptureTest {
    private val targetFile = "ReaderSelectionCallbacks.kt"

    @Test
    fun `selection callbacks must not capture the annotation state parameter inside remember`() {
        val file =
            Konsist
                .scopeFromProduction()
                .files
                .firstOrNull { it.path.endsWith(targetFile) }

        assertTrue(
            "Could not resolve $targetFile from the production scope; " +
                "the Konsist production scope could not be resolved",
            file != null,
        )

        val source = file!!.text

        // Every `remember(...) { ... }` block body. The `derivedStateOf` helper
        // is exempt: a derived value that lists the state it reads among its own
        // `remember` keys is the correct shape — it re-creates when that state
        // changes, unlike a callback lambda keyed only on `viewModel`.
        val offenders =
            rememberBlockBodies(source)
                .filterNot { body -> DERIVED_STATE_READ.containsMatchIn(body) }
                .filter { body -> BARE_ANNOTATION_READ.containsMatchIn(body) }
                .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "$targetFile reads the captured `annotationUiState` parameter inside a remember " +
                    "block. That freezes the state at first composition and breaks the callback " +
                    "when the selection arrives later (reader bug 3). Read the live slice inside " +
                    "the lambda instead (`viewModel.annotationUiState.value`):\n" +
                    offenders.joinToString("\n") { "  - remember body: ${it.trim().take(120)}" },
            )
        }
    }

    /**
     * A read of the captured `annotationUiState` *parameter*. The negative
     * lookbehind keeps the live slice (`viewModel.annotationUiState.value`)
     * out of scope.
     */
    private val BARE_ANNOTATION_READ = Regex("""(?<!\.)\bannotationUiState\s*\.""")

    /**
     * The `derivedStateOf { … }` builder — the one legitimate captured read.
     * A derived value lists the state it reads among its own `remember` keys,
     * so it does re-create; a callback lambda keyed only on `viewModel` does not.
     */
    private val DERIVED_STATE_READ = Regex("""\bderivedStateOf\s*\{""")

    /**
     * Extracts the body of each `remember(` call, from the opening brace to its
     * matching closing brace. Brace-depth counting is enough for this file:
     * the bodies contain no braces inside string literals or comments.
     */
    private fun rememberBlockBodies(source: String): Sequence<String> = sequence {
        var index = 0
        while (index < source.length) {
            val callStart = source.indexOf("remember(", index)
            if (callStart == -1) break

            val bodyStart = source.indexOf('{', callStart)
            if (bodyStart == -1) break

            var depth = 0
            var cursor = bodyStart
            while (cursor < source.length) {
                when (source[cursor]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                cursor++
            }

            if (depth == 0 && cursor > bodyStart) {
                yield(source.substring(bodyStart + 1, cursor))
            }
            index = if (cursor > callStart) cursor + 1 else callStart + 1
        }
    }
}
