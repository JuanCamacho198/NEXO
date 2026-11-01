package com.nexo.presentation.screen.readium

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commitNow
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFragment

private const val EPUB_NAVIGATOR_TAG = "ReadiumNavigator"
private const val PDF_NAVIGATOR_TAG = "ReadiumPdfNavigator"

/**
 * Readium navigator fragments have no no-arg constructor: they are built by a
 * [FragmentFactory] created from a [org.readium.r2.shared.publication.Publication].
 * The Compose hosts install that factory, which runs long after `super.onCreate`.
 *
 * On activity recreation (configuration change, process death) the FragmentManager
 * restores saved navigator fragments during `attachController` — inside
 * `super.onCreate`. With no factory registered yet, restoration throws
 * `Fragment$InstantiationException: could not find Fragment constructor` and the
 * activity cannot start.
 *
 * Registering the Readium "dummy" factory here, before `super.onCreate`, lets
 * restoration succeed. The restored dummy is a real navigator fragment backed by
 * an inert placeholder publication, so it must be evicted before `onResume`,
 * otherwise Readium throws [org.readium.r2.navigator.RestorationNotSupportedException].
 * [evictRestoredReadiumNavigatorFragments] does that eviction.
 *
 * See the "Limitations of the current `Fragment` APIs" section of the Readium
 * navigator guide.
 */
fun FragmentActivity.installReadiumDummyFragmentFactories() {
    val epubDummy = EpubNavigatorFragment.createDummyFactory()
    val pdfDummy = PdfNavigatorFragment.createDummyFactory(PdfiumEngineProvider())
    supportFragmentManager.fragmentFactory =
        object : FragmentFactory() {
            override fun instantiate(
                classLoader: ClassLoader,
                className: String,
            ) = when (className) {
                EpubNavigatorFragment::class.java.name -> epubDummy.instantiate(classLoader, className)
                PdfNavigatorFragment::class.java.name -> pdfDummy.instantiate(classLoader, className)
                else -> super.instantiate(classLoader, className)
            }
        }
}

/**
 * Removes any navigator fragment restored from saved state by
 * [installReadiumDummyFragmentFactories]. Must be called after `super.onCreate`
 * and before `onResume`.
 *
 * Uses [commitNow] because the eviction is order-sensitive: Readium asserts the
 * dummy is gone by `onResume`, and an async `commit` could land after it.
 */
fun FragmentActivity.evictRestoredReadiumNavigatorFragments() {
    val manager = supportFragmentManager
    listOf(EPUB_NAVIGATOR_TAG, PDF_NAVIGATOR_TAG)
        .mapNotNull(manager::findFragmentByTag)
        .takeIf { it.isNotEmpty() }
        ?.let { restored ->
            manager.commitNow { restored.forEach(::remove) }
        }
}
