package com.nexo.presentation.navigation

import com.nexo.R
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.presentation.feature.legal.addonCapabilitiesRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discover destinations exist with their routes and labels. v1 keeps Discover
 * out of the bottom bar (entry via Home/Library actions); the "Ver todo" section
 * list is a second destination inside the same graph, now a typed
 * [DiscoverSectionRoute].
 *
 * The generated destination pattern (`discover/section?...`) and the
 * special-character round-trip through a real NavController are asserted in
 * TypedRoutesNavigationTest; this file keeps the argument contract guards.
 */
class DiscoverNavTest {
    @Test
    fun discover_usesDiscoverRoute() {
        assertEquals("discover", NexoDestination.Discover.route)
    }

    @Test
    fun discover_usesNavDiscoverLabel() {
        assertEquals(R.string.nav_discover, NexoDestination.Discover.labelRes)
    }

    @Test
    fun discoverSection_typedRouteCarriesTitleAndBothSelectorDefaults() {
        // All three query arguments are optional and default to blank, matching
        // the `defaultValue = ""` the string route used.
        val route = DiscoverSectionRoute()
        assertEquals("", route.sectionTitle)
        assertEquals("", route.sort)
        assertEquals("", route.sourceId)
    }

    @Test
    fun discoverSectionRoute_carriesLocalizedTitleAndSortSelector() {
        val route =
            DiscoverSectionRoute(
                sectionTitle = "Recién agregados al catálogo",
                sort = CatalogFeaturedSort.NEWEST.name,
                sourceId = "",
            )

        // The already-localized copy rides on the typed route untouched:
        // Navigation owns percent-encoding, so nothing is pre-encoded here.
        assertEquals("Recién agregados al catálogo", route.sectionTitle)
        assertTrue("localized title must keep its spaces", route.sectionTitle.contains(" "))
        assertFalse("localized title must not be pre-encoded", route.sectionTitle.contains("%"))
        assertEquals("NEWEST", route.sort)
        assertEquals("", route.sourceId)
    }

    @Test
    fun discoverSectionRoute_carriesSourceSelectorOnly() {
        val route = DiscoverSectionRoute(sectionTitle = "Gutendex", sort = "", sourceId = "builtin:gutendex")

        assertEquals("", route.sort)
        assertEquals("builtin:gutendex", route.sourceId)
        assertTrue("source id must not be pre-encoded", route.sourceId.contains(":"))
    }

    @Test
    fun settingsLegal_usesLegalRoute() {
        assertEquals("settings/legal", NexoDestination.SettingsLegal.route)
    }

    @Test
    fun addonCapabilitiesRoute_carriesAddonId() {
        val route = addonCapabilitiesRoute("abc123")
        assertEquals("settings/addon-capabilities/abc123", route)
        assertTrue(NexoDestination.SettingsAddonCapabilities.route.contains("{addonId}"))
    }
}
