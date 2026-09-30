package com.nexo.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Konsist rule (SDD android-stack-modernization S14, closing the provider-confinement
 * gap found by the S13 closure review / Engram #2781).
 *
 * The spec clause "ViewModel resolution MUST be confined to the designated providers"
 * has two halves:
 *  1. [NoInlineViewModelResolutionTest] bans `viewModel()`/`hiltViewModel()` calls
 *     outside the designated provider file.
 *  2. this rule bans constructing a `*ViewModel(...)` directly inside a `@Composable`
 *     body — the loophole that left three ViewModels built inside `remember {}` blocks
 *     in `SettingsScreen.kt` after S13.
 *
 * Exemptions, all narrow:
 *  - the designated provider file — the one legitimate construction site;
 *  - `@Preview` composables — preview-only fixture instances;
 *  - `*Factory(...)` construction is not matched at all, because the pattern requires
 *    an identifier ending in `ViewModel` immediately before the `(`.
 *
 * Third-party code is never scanned: the scope is the first-party `src/main`
 * production source set, and the negative fixture lives in this module's test
 * resources. The fixture is committed so the rule's bite is reproducible from the
 * repo instead of only described.
 */
class DirectViewModelConstructionTest {
    private val designatedProviderFile = "ViewModelProviders.kt"

    /**
     * A standalone identifier ending in `ViewModel` immediately followed by `(`.
     *
     * The negative lookbehind keeps `rememberXViewModel(...)` and `hiltViewModel(...)`
     * out (the preceding word character blocks the inner `XViewModel(` match), while
     * standalone `XViewModel(...)` calls still match, including multi-line ones.
     */
    private val directConstruction = Regex("""(?<![A-Za-z0-9_])[A-Z]\w*ViewModel\s*\(""")

    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    @Test
    fun `production composables never construct a ViewModel directly`() {
        val scope = Konsist.scopeFromProduction()

        assertTrue(
            "Konsist found no production files; the production scope could not be resolved",
            scope.files.any { it.path.normalizedPath().endsWith("NexoNavHost.kt") } &&
                scope.files.any { it.path.normalizedPath().endsWith(designatedProviderFile) },
        )

        val offenders = scope.offendingConstructions()

        if (offenders.isNotEmpty()) {
            fail(
                "ViewModel resolution must be confined to $designatedProviderFile; " +
                    "direct *ViewModel(...) construction found inside composable bodies:\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    fun `the rule flags a direct construction in a composable body`() {
        val offenders = Konsist.scopeFromFile(NEGATIVE_FIXTURE_PROJECT_PATH).offendingConstructions()

        assertEquals(
            "The committed negative fixture must be flagged exactly once — and only its " +
                "non-Preview composable, never its *Factory(...)/remember*ViewModel(...)/" +
                "@Preview exemptions: $NEGATIVE_FIXTURE_PROJECT_PATH",
            1,
            offenders.size,
        )

        val offender = offenders.single()
        assertTrue(
            "The flagged composable must be the fixture's direct-construction composable: $offender",
            offender.endsWith("::$NEGATIVE_FIXTURE_COMPOSABLE"),
        )
        assertTrue(
            "The flagged declaration must live in the committed fixture file: $offender",
            offender.substringBeforeLast("::").normalizedPath().endsWith(NEGATIVE_FIXTURE_FILE_SUFFIX),
        )
    }

    private fun KoScope.offendingConstructions(): List<String> =
        functions(includeNested = true, includeLocal = true)
            .filterNot { it.isInDesignatedProviderFile() }
            .filter { it.hasAnnotationWithName(COMPOSABLE) }
            .filterNot { it.hasAnnotationWithName(PREVIEW) }
            .filter { directConstruction.containsMatchIn(it.text.withoutComments()) }
            .map { "${it.containingFile?.path?.normalizedPath()}::${it.name}" }

    private fun KoFunctionDeclaration.isInDesignatedProviderFile(): Boolean = containingFile?.path?.normalizedPath()?.endsWith(designatedProviderFile) == true

    private fun String.normalizedPath(): String = replace('\\', '/')

    private fun String.withoutComments(): String = replace(blockComment, " ").replace(lineComment, " ")

    private companion object {
        const val COMPOSABLE = "Composable"
        const val PREVIEW = "Preview"

        /** Committed negative fixture (Engram #2781 suggestion S1). */
        const val NEGATIVE_FIXTURE_PROJECT_PATH =
            "app/src/test/resources/konsist-fixtures/direct-viewmodel-construction/DirectViewModelConstructionFixture.kt"

        const val NEGATIVE_FIXTURE_FILE_SUFFIX =
            "konsist-fixtures/direct-viewmodel-construction/DirectViewModelConstructionFixture.kt"

        const val NEGATIVE_FIXTURE_COMPOSABLE = "DirectViewModelConstructionFixture"
    }
}
