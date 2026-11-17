package com.nexo.data.update

import android.content.Context
import android.content.pm.PackageManager
import com.nexo.domain.update.UpdateInstallSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Installer-source guard for the self-updater (B3).
 *
 * `getInstallSourceInfo` is API 30 and the JVM unit tests run against an older
 * `Build.VERSION.SDK_INT`, so the tested path here is the fallback
 * `getInstallerPackageName`. The API 30 branch is the same value one call later
 * and is exercised on device.
 */
class AndroidUpdateInstallSourceReaderTest {
    @After
    fun tearDown() = unmockkAll()

    private fun readerReporting(installer: String?): AndroidUpdateInstallSourceReader {
        val packageManager =
            mockk<PackageManager> {
                every { getInstallerPackageName("com.nexo") } returns installer
            }
        val context =
            mockk<Context> {
                every { packageName } returns "com.nexo"
                every { getPackageManager() } returns packageManager
            }
        return AndroidUpdateInstallSourceReader(context)
    }

    @Test
    fun storeInstalledBuild_reportsStore() {
        assertEquals(UpdateInstallSource.STORE, readerReporting("com.android.vending").current())
    }

    @Test
    fun sideloadedBuild_reportsSideload() {
        assertEquals(UpdateInstallSource.SIDELOAD, readerReporting("com.android.packageinstaller").current())
    }

    @Test
    fun unknownInstaller_reportsUnknown() {
        assertEquals(UpdateInstallSource.UNKNOWN, readerReporting(null).current())
    }

    @Test
    fun packageManagerThrows_reportsUnknownInsteadOfCrashing() {
        val packageManager =
            mockk<PackageManager> {
                every { getInstallerPackageName(any()) } throws IllegalArgumentException("no installer")
            }
        val context =
            mockk<Context> {
                every { packageName } returns "com.nexo"
                every { getPackageManager() } returns packageManager
            }
        assertEquals(UpdateInstallSource.UNKNOWN, AndroidUpdateInstallSourceReader(context).current())
    }

    @Test
    fun classify_isPure() {
        assertEquals(UpdateInstallSource.STORE, AndroidUpdateInstallSourceReader.classify("com.android.vending"))
        assertEquals(UpdateInstallSource.SIDELOAD, AndroidUpdateInstallSourceReader.classify("com.aurora.store"))
        assertEquals(UpdateInstallSource.UNKNOWN, AndroidUpdateInstallSourceReader.classify(null))
    }
}
