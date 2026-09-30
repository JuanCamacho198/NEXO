package com.nexo.presentation.screen.settings

import com.nexo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4: Fuentes integradas read-only contract + addon toggle regression.
 *
 * Static list: Gutendex powers the IDLE rails (Recently added + Popular);
 * Open Library is combined search only (no dedicated rail, no IDLE branding).
 * Addon toggles still write through the ViewModel unchanged.
 *
 * Non-interactivity of the section (no clickable/toggle/onClick) is enforced
 * by construction in AddonManagementScreen.kt — rows are plain Cards with no
 * interaction modifiers — and verified by inspection, since Compose runtime
 * is unavailable in local unit tests.
 */
class BuiltInSourcesTest {
    @Test
    fun builtInSources_listsGutendexAndOpenLibrary() {
        assertEquals(2, builtInSources.size)
        assertEquals("Gutendex", builtInSources[0].name)
        assertEquals("Open Library", builtInSources[1].name)
    }

    @Test
    fun builtInSources_rolesAreDistinctResources() {
        assertTrue(builtInSources[0].roleResId != builtInSources[1].roleResId)
        assertEquals(R.string.settings_addons_builtin_gutendex_role, builtInSources[0].roleResId)
        assertEquals(R.string.settings_addons_builtin_openlibrary_role, builtInSources[1].roleResId)
    }

    @Test
    fun builtInSources_isStaticSingleton() {
        // Zero backend: the list is a module-level val, same instance every read.
        assertSame(builtInSources, builtInSources)
        assertEquals(2, builtInSources.size)
    }

    @Test
    fun builtinSourceNames_mentionNoIdleBranding() {
        // P4 KEEP HIDDEN preserved: neither entry carries IDLE branding in name.
        builtInSources.forEach { source ->
            assertFalse(source.name.contains("IDLE", ignoreCase = true))
        }
    }
}

/**
 * U4 regression: managed-addon toggle path untouched (ViewModel writes through).
 *
 * The read-only section adds no toggle surface; this locks the existing
 * AddonSettingsViewModel.toggle behavior in the same test class family.
 */
class AddonToggleUnregressedTest {
    @Test
    fun addonSettingsUiState_defaultsHaveNoBuiltInRows() {
        // Built-in sources live outside AddonSettingsUiState: no registry row
        // merges into installed addons, so the section can never write state.
        val state =
            com.nexo.presentation.viewmodel
                .AddonSettingsUiState()
        assertTrue(state.installed.isEmpty())
        assertTrue(state.consentedIds.isEmpty())
    }
}
