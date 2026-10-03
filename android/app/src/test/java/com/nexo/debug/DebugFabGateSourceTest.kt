package com.nexo.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FR-AD1 guard: the debug FAB gate in `NexoNavHost` must depend on the
 * persisted `DebugPrefs` toggle ALONE. The previous
 * `userId.startsWith("local-")` conjunct must never come back.
 */
class DebugFabGateSourceTest {
    @Test
    fun `debug fab gate is DebugPrefs alone with no local user check`() {
        val normalized = locateNavHostSource().replace(Regex("\\s+"), " ")

        assertTrue(
            "NexoNavHost must gate the debug FAB on DebugPrefs alone",
            normalized.contains("showDebugFab = DebugPrefs.isEnabled(context)"),
        )
        assertFalse(
            "The userId startsWith(\"local-\") gate must stay removed (FR-AD1)",
            normalized.contains("startsWith(\"local-\")"),
        )
    }

    private fun locateNavHostSource(): String {
        val relative = "com/nexo/presentation/navigation/NexoNavHost.kt"
        val roots =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .take(5)
                .flatMap { dir ->
                    sequenceOf(
                        File(dir, "src/main/java/$relative"),
                        File(dir, "app/src/main/java/$relative"),
                    )
                }
        val file =
            roots.firstOrNull { it.exists() }
                ?: error("NexoNavHost.kt not found from ${File("").absolutePath}")
        return file.readText()
    }
}
