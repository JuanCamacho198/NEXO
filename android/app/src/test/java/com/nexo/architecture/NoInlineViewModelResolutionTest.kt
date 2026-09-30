package com.nexo.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Konsist rule (SDD android-stack-modernization R9 / SC9.1-9.2).
 *
 * ViewModel resolution in production must be single-sourced in
 * ViewModelProviders.kt; no composable — host, feature NavGraph builder, feature
 * screen, or settings route — may call `viewModel()` or `hiltViewModel()`
 * directly. Callers receive the resolved instance as a parameter instead (or, for
 * route-scoped VMs, through the `remember*ViewModel` providers in that file).
 *
 * Scope note: S2 enforced this over `/presentation/navigation/` only, because
 * four screen-level resolvers lived outside the navigation layer. S13 hoisted
 * those four (PerformanceScreen, AddonManagementRoute, EditBookMetadataScreen,
 * BookDetailScreen) and widened the scope to the whole production source set, so
 * the rule now matches the spec requirement literally.
 */
class NoInlineViewModelResolutionTest {
    private val designatedProviderFile = "ViewModelProviders.kt"

    /** Matches `viewModel(` / `viewModel<T>(` and the `hiltViewModel` variants
     * (word-boundary anchored, so the `hiltViewModel` alternative is not
     * double-counted as `viewModel`). */
    private val inlineResolution = Regex("""\b(?:viewModel|hiltViewModel)\b\s*[<(]""")

    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    @Test
    fun `production code resolves ViewModels only through the designated provider`() {
        val productionFiles = Konsist.scopeFromProduction().files

        assertTrue(
            "Konsist found no production files; the production scope could not be resolved",
            productionFiles.any { it.path.normalizedPath().endsWith("NexoNavHost.kt") } &&
                productionFiles.any { it.path.normalizedPath().endsWith(designatedProviderFile) },
        )

        val offenders =
            productionFiles
                .filterNot { it.path.normalizedPath().endsWith(designatedProviderFile) }
                .filter { file -> inlineResolution.containsMatchIn(file.text.withoutComments()) }
                .map { it.path.normalizedPath() }

        if (offenders.isNotEmpty()) {
            fail(
                "ViewModel resolution must be confined to $designatedProviderFile; " +
                    "inline viewModel()/hiltViewModel() found in production code:\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
    }

    private fun String.normalizedPath(): String = replace('\\', '/')

    private fun String.withoutComments(): String = replace(blockComment, " ").replace(lineComment, " ")
}
