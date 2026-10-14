package com.nexo.data.remote.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U2: the source label the user reads comes from one place — the catalog
 * provider's advertised [CatalogSourceInfo.name]. Discover's filter chip and
 * its attribution badge both read that value, so a label change lands on both
 * surfaces at once and they cannot drift.
 *
 * The upstream service is Gutendex; the catalog it serves is Project Gutenberg,
 * which is the label the user sees (desktop `providerLabel()` agrees).
 */
class BuiltInCatalogProviderLabelTest {
    @Test
    fun gutendexBuiltInSourceIdMapsToTheGutenbergLabel() {
        assertEquals("Gutenberg", PROVIDER_LABEL_GUTENDEX)
        assertEquals(BUILTIN_GUTENDEX, CatalogSources.GUTENDEX)
        // The id names the service; only the label names the catalog.
        assertEquals("Gutendex", CatalogSources.GUTENDEX.removePrefix("builtin:").replaceFirstChar { it.uppercase() })
    }

    @Test
    fun openLibraryLabelIsUnchanged() {
        assertEquals("Open Library", PROVIDER_LABEL_OPENLIBRARY)
    }
}
