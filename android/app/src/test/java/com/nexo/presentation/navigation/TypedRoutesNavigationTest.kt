package com.nexo.presentation.navigation

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the typed Reader/DiscoverSection routes resolve through a real
 * [NavHostController]:
 *
 * 1. the generated destination route pattern is byte-for-byte the hand-written
 *    route string it replaced (`reader?...` / `discover/section?...`), and
 * 2. argument values — including paths carrying spaces, `/`, `&`, `+`, `%`,
 *    `#`, `?` and unicode — round-trip without corruption or double-encoding.
 *
 * Robolectric supplies the real Android `Uri`/`Bundle` runtime that Navigation
 * uses to encode and decode routes, so this is a device-free proof of exactly
 * the behaviour the deleted `encodeRouteParam`/`encodeQueryValue` helpers owned.
 * The instrumented twin lives in NexoNavHostStackCollapseTest.
 */
@RunWith(RobolectricTestRunner::class)
class TypedRoutesNavigationTest {
    @Test
    fun `ReaderRoute destination pattern is the verbatim legacy route`() {
        val controller = controllerWith(ReaderRoute()) { composable<ReaderRoute> { } }

        assertEquals(
            "reader?bookId={bookId}&bookPath={bookPath}&bookFormat={bookFormat}",
            controller.currentDestination?.route,
        )
    }

    @Test
    fun `ReaderRoute round-trips a book path with special characters`() {
        val controller = controllerWith(ReaderRoute()) { composable<ReaderRoute> { } }
        val path = "/files/a+b & cömic/第一章/100% #1?.epub"

        controller.navigate(ReaderRoute(bookId = "book-123", bookPath = path, bookFormat = "epub"))

        val decoded = controller.currentBackStackEntry!!.toRoute<ReaderRoute>()
        assertEquals("book-123", decoded.bookId)
        assertEquals(path, decoded.bookPath)
        assertEquals("epub", decoded.bookFormat)
    }

    @Test
    fun `ReaderRoute bare navigation resolves its declared defaults`() {
        val controller = controllerWith(ReaderRoute()) { composable<ReaderRoute> { } }

        controller.navigate(ReaderRoute())

        val decoded = controller.currentBackStackEntry!!.toRoute<ReaderRoute>()
        assertEquals("", decoded.bookId)
        assertNull(decoded.bookPath)
        assertEquals("epub", decoded.bookFormat)
    }

    @Test
    fun `ReaderRoute round-trips an explicit null path`() {
        val controller = controllerWith(ReaderRoute()) { composable<ReaderRoute> { } }

        controller.navigate(ReaderRoute(bookId = "book-123", bookPath = null, bookFormat = "pdf"))

        val decoded = controller.currentBackStackEntry!!.toRoute<ReaderRoute>()
        assertEquals("book-123", decoded.bookId)
        assertNull(decoded.bookPath)
        assertEquals("pdf", decoded.bookFormat)
    }

    @Test
    fun `DiscoverSectionRoute destination pattern is the verbatim legacy route`() {
        val controller = controllerWith(DiscoverSectionRoute()) { composable<DiscoverSectionRoute> { } }

        assertEquals(
            "discover/section?sectionTitle={sectionTitle}&sort={sort}&sourceId={sourceId}",
            controller.currentDestination?.route,
        )
    }

    @Test
    fun `DiscoverSectionRoute round-trips a localized title with special characters`() {
        val controller = controllerWith(DiscoverSectionRoute()) { composable<DiscoverSectionRoute> { } }
        val title = "Recién agregados al catálogo & más"

        controller.navigate(DiscoverSectionRoute(sectionTitle = title, sort = "", sourceId = "builtin:gutendex"))

        val decoded = controller.currentBackStackEntry!!.toRoute<DiscoverSectionRoute>()
        assertEquals(title, decoded.sectionTitle)
        assertEquals("", decoded.sort)
        assertEquals("builtin:gutendex", decoded.sourceId)
    }

    @Test
    fun `DiscoverSectionRoute featured sort round-trips the selector name`() {
        val controller = controllerWith(DiscoverSectionRoute()) { composable<DiscoverSectionRoute> { } }

        controller.navigate(DiscoverSectionRoute(sectionTitle = "Nuevos", sort = "NEWEST", sourceId = ""))

        val decoded = controller.currentBackStackEntry!!.toRoute<DiscoverSectionRoute>()
        assertEquals("Nuevos", decoded.sectionTitle)
        assertEquals("NEWEST", decoded.sort)
        assertEquals("", decoded.sourceId)
    }
}

/** A NavController with the same wiring `NavHost` installs, minus composition. */
private inline fun controllerWith(
    startDestination: Any,
    crossinline builder: NavGraphBuilder.() -> Unit,
): NavHostController {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = NavHostController(context)
    controller.navigatorProvider.addNavigator(ComposeNavigator())
    val owner = TestNavHostOwner()
    controller.setLifecycleOwner(owner)
    controller.setViewModelStore(owner.viewModelStore)
    controller.setOnBackPressedDispatcher(owner.onBackPressedDispatcher)
    controller.setGraph(controller.createGraph(startDestination = startDestination) { builder() }, null)
    return controller
}

private class TestNavHostOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }
}
