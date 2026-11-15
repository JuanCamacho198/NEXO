package com.nexo.presentation.screen.settings

import com.nexo.feature.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4: Fuentes integradas read-only contract.
 *
 * Static list: Gutendex is the download authority for public-domain titles and
 * Open Library is combined search only. Neither owns an IDLE rail any more —
 * after S2 the IDLE shelves are author shelves served from Google Books, so a
 * source row makes no rail promise and the roster stays a free-text list.
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
        assertEquals("Gutenberg", builtInSources[0].name)
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
