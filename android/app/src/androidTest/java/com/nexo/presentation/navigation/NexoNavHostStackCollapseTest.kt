package com.nexo.presentation.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose test proving the bottom-nav home-reset fix: from any
 * bottom-nav tab (Estantería / Resaltados / Ajustes), tapping Inicio collapses
 * the back stack to `[home]` instead of stacking a fresh Home entry on top.
 *
 * Uses a [TestNavHostController] mini-graph mirroring the real
 * `bottomNavDestinations` and drives navigation through the exact production
 * helper [navigateToBottomTab] used by [NexoNavHost].
 *
 * NOTE: requires a device/emulator to run (androidTest). Compile-level
 * verification is done via `:app:compileDebugAndroidTestKotlin`.
 */
@RunWith(AndroidJUnit4::class)
class NexoNavHostStackCollapseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    private val otherTabs =
        listOf(
            NexoDestination.Library.route,
            NexoDestination.Highlights.route,
            NexoDestination.Settings.route,
        )

    private fun launchMiniGraph() {
        composeRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            NavHost(
                navController = navController,
                startDestination = NexoDestination.Home.route,
            ) {
                composable(NexoDestination.Home.route) { Text("Home") }
                composable(NexoDestination.Library.route) { Text("Library") }
                composable(NexoDestination.Highlights.route) { Text("Highlights") }
                composable(NexoDestination.Settings.route) { Text("Settings") }
            }
        }
        composeRule.waitForIdle()
    }

    /** Simulates a bottom-nav tap using the same options as [NexoNavHost]. */
    private fun tapTab(route: String) {
        composeRule.runOnUiThread {
            navController.navigateToBottomTab(
                route = route,
                homeRoute = NexoDestination.Home.route,
            )
        }
        composeRule.waitForIdle()
    }

    private fun currentStack(): List<String> {
        var routes: List<String> = emptyList()
        composeRule.runOnUiThread {
            routes = navController.backStack.mapNotNull { it.destination.route }
        }
        return routes
    }

    // D8/R8 reject spaces in a DEX SimpleName below DEX version 040, so a
    // backtick-quoted name like `Inicio tap collapses ...` makes
    // `:app:dexBuilderDebugAndroidTest` fail with "Space characters in
    // SimpleName ... are not allowed prior to DEX version 040" — the
    // instrumented APK could never be built, let alone run.
    @Test
    fun inicioTap_collapsesStackToHomeFromEveryTab() {
        launchMiniGraph()

        otherTabs.forEach { tab ->
            // Navigate to the tab first — classic bottom-nav behavior.
            tapTab(tab)
            assertEquals(
                "expected [home, $tab] before tapping Inicio",
                listOf(NexoDestination.Home.route, tab),
                currentStack(),
            )

            // The fix: Inicio tap must collapse the stack to [home].
            tapTab(NexoDestination.Home.route)
            assertEquals(
                "Inicio tap from $tab must collapse the stack to [home]",
                listOf(NexoDestination.Home.route),
                currentStack(),
            )
        }
    }

    /**
     * Instrumented twin of `TypedRoutesNavigationTest`: the typed Reader route
     * keeps the verbatim destination pattern and round-trips a book path with
     * spaces, `/`, `&`, `+`, `%`, `#`, `?` and unicode through a real NavHost.
     */
    // Same DEX SimpleName constraint as above: no spaces in the method name.
    @Test
    fun typedReaderRoute_roundTripsSpecialCharacterBookPath() {
        val specialPath = "/files/a+b & cömic/第一章/100% #1?.epub"

        composeRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            NavHost(
                navController = navController,
                startDestination = ReaderRoute(),
            ) {
                composable<ReaderRoute> { Text("Reader") }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            navController.navigate(ReaderRoute(bookId = "book-123", bookPath = specialPath, bookFormat = "epub"))
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            val entry = checkNotNull(navController.currentBackStackEntry)
            assertEquals(
                "reader?bookId={bookId}&bookPath={bookPath}&bookFormat={bookFormat}",
                entry.destination.route,
            )
            val decoded = entry.toRoute<ReaderRoute>()
            assertEquals("book-123", decoded.bookId)
            assertEquals("special-character paths must round-trip verbatim", specialPath, decoded.bookPath)
            assertEquals("epub", decoded.bookFormat)
        }
    }
}
