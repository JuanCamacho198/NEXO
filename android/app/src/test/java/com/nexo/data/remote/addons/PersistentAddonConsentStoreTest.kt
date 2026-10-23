package com.nexo.data.remote.addons

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `library_meta` is its own scope: per addon, default off, stored beside the
 * disclosure consent and never implied by it.
 */
@RunWith(RobolectricTestRunner::class)
class PersistentAddonConsentStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun libraryMetaConsent_defaultsOff() {
        val store = PersistentAddonConsentStore(context)

        assertFalse(store.hasLibraryMetaConsent("addon-default-off"))
    }

    @Test
    fun libraryMetaConsent_isNeverImpliedByStreamConsent() {
        val store = PersistentAddonConsentStore(context)

        store.recordConsent("addon-stream-only")

        assertTrue(store.hasConsent("addon-stream-only"))
        assertFalse(store.hasLibraryMetaConsent("addon-stream-only"))
    }

    @Test
    fun libraryMetaConsent_survivesRestart() {
        PersistentAddonConsentStore(context).setLibraryMetaConsent("addon-durable", true)

        assertTrue(PersistentAddonConsentStore(context).hasLibraryMetaConsent("addon-durable"))
    }

    @Test
    fun libraryMetaConsent_isRevocable() {
        val store = PersistentAddonConsentStore(context)
        store.setLibraryMetaConsent("addon-revoked", true)

        store.setLibraryMetaConsent("addon-revoked", false)

        assertFalse(store.hasLibraryMetaConsent("addon-revoked"))
    }
}
