package com.nexo.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Konsist rule (SDD android-stack-modernization S3, R7 / SC9.1-9.2).
 *
 * The project's own `domain` layer must carry no Android runtime dependency:
 * no `android.*` import or fully-qualified reference, and no `androidx.*`
 * dependency. The single exception is `androidx.compose.runtime.Immutable`, a
 * JVM-only `@Immutable` annotation Compose reads at compile time and which
 * does not pull the Android framework into `domain`.
 *
 * Exemptions are limited to that third-party annotation; no project file is
 * ever allowlisted — a violation is fixed by removing the dependency, not by
 * hiding the file.
 */
class DomainPurityTest {
    private val domainMarker = "/com/nexo/domain/"
    private val allowedThirdParty = setOf("androidx.compose.runtime.Immutable")

    /** `android.`/`androidx.` reference (import or FQN). The lookbehind keeps
     * `android` inside a longer identifier (`com.android.*`) out of scope. */
    private val androidReference = Regex("""(?<![\w.])(androidx?\.[\w.]+)""")

    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    @Test
    fun `domain layer depends on no Android framework or AndroidX type`() {
        val domainFiles =
            Konsist
                .scopeFromProduction()
                .files
                .filter { it.path.normalizedPath().contains(domainMarker) }

        assertTrue(
            "Konsist found no production domain files; the production scope could not be resolved",
            domainFiles.size >= MIN_EXPECTED_DOMAIN_FILES &&
                domainFiles.any { it.path.normalizedPath().endsWith("DeviceInfo.kt") },
        )

        val offenders =
            domainFiles.mapNotNull { file ->
                val bannedImports =
                    file.imports
                        .map { it.name }
                        .filter { it.isBannedReference() }
                val bannedFqn =
                    androidReference
                        .findAll(file.text.withoutComments())
                        .map { it.groupValues[1] }
                        .filter { it.isBannedReference() }
                        .distinct()
                val banned = (bannedImports + bannedFqn).distinct()
                if (banned.isEmpty()) null else file.path.normalizedPath() to banned
            }

        if (offenders.isNotEmpty()) {
            fail(
                "The project `domain` layer must not depend on android.*/androidx.* " +
                    "(allowed third-party only: $allowedThirdParty):\n" +
                    offenders.joinToString("\n") { (path, banned) ->
                        "  - $path -> ${banned.joinToString(", ")}"
                    },
            )
        }
    }

    private fun String.isBannedReference(): Boolean {
        val isAndroid = this == "android" || startsWith("android.") || startsWith("androidx.")
        if (!isAndroid) return false
        return allowedThirdParty.none { allowed -> this == allowed || startsWith("$allowed.") }
    }

    private fun String.normalizedPath(): String = replace('\\', '/')

    private fun String.withoutComments(): String = replace(blockComment, " ").replace(lineComment, " ")

    private companion object {
        /**
         * Guard against a silently empty/degraded production scope: the module
         * has 36 production `domain` files today.
         */
        const val MIN_EXPECTED_DOMAIN_FILES = 25
    }
}
