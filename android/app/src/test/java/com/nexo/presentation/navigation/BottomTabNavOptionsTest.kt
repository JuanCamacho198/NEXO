package com.nexo.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomTabNavOptionsTest {
    // ── Inicio (Home) — special case ──────────────────────────────────

    @Test
    fun `Inicio tap pops up to Home non-inclusive with launchSingleTop and no save-restore`() {
        val options =
            BottomTabNavOptions.forRoute(
                route = NexoDestination.Home.route,
                homeRoute = NexoDestination.Home.route,
            )

        assertEquals("popUpTo must target the Home route", NexoDestination.Home.route, options.popUpToRoute)
        assertFalse("Inicio popUpTo must be non-inclusive", options.popUpToInclusive)
        assertTrue("Inicio tap must launch single top", options.launchSingleTop)
        assertFalse("Inicio must not restore the previous tab's state", options.restoreState)
        assertFalse("Inicio must not save the previous tab's state", options.saveState)
    }

    // ── Other tabs — classic save/restore ─────────────────────────────

    @Test
    fun `other tabs keep restoreState and saveState around Home`() {
        val otherTabs =
            listOf(
                NexoDestination.Library,
                NexoDestination.Highlights,
                NexoDestination.Settings,
            )

        otherTabs.forEach { tab ->
            val options =
                BottomTabNavOptions.forRoute(
                    route = tab.route,
                    homeRoute = NexoDestination.Home.route,
                )

            assertEquals("${tab.route}: popUpTo must target the Home route", NexoDestination.Home.route, options.popUpToRoute)
            assertFalse("${tab.route}: popUpTo must be non-inclusive", options.popUpToInclusive)
            assertTrue("${tab.route}: tab tap must launch single top", options.launchSingleTop)
            assertTrue("${tab.route}: tab tap must restore state", options.restoreState)
            assertTrue("${tab.route}: tab tap must save state", options.saveState)
        }
    }

    // ── Membership guard (design open question) ───────────────────────

    @Test
    fun `every bottom nav destination resolves correct options`() {
        val bottomNavDestinations =
            listOf(
                NexoDestination.Home,
                NexoDestination.Library,
                NexoDestination.Discover,
                NexoDestination.Highlights,
                NexoDestination.Settings,
            )

        bottomNavDestinations.forEach { dest ->
            val options =
                BottomTabNavOptions.forRoute(
                    route = dest.route,
                    homeRoute = NexoDestination.Home.route,
                )
            // Home is the only destination without save/restore.
            val isHome = dest.route == NexoDestination.Home.route
            assertEquals(isHome, !options.restoreState)
            assertEquals(isHome, !options.saveState)
        }
    }

    // ── Route whitelist (S12: typed Reader/DiscoverSection must not enter it) ──

    @Test
    fun `bottom nav whitelist stays the five verbatim tab routes`() {
        // The bottom nav is built from `bottomNavDestinations.map { it.route }`.
        // Only Reader and DiscoverSection moved to typed routes, so these five
        // string routes must stay byte-identical (the bar must not change).
        val bottomNavDestinations =
            listOf(
                NexoDestination.Home,
                NexoDestination.Library,
                NexoDestination.Discover,
                NexoDestination.Highlights,
                NexoDestination.Settings,
            )

        assertEquals(
            listOf("home", "library", "discover", "highlights", "settings"),
            bottomNavDestinations.map { it.route },
        )
    }
}
