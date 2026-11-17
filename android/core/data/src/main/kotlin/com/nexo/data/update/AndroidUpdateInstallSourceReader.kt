package com.nexo.data.update

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.os.Build
import com.nexo.domain.update.UpdateInstallSource
import com.nexo.domain.update.UpdateInstallSourceReader

/**
 * Reads the installer package of the running build.
 *
 * `PackageManager.getInstallSourceInfo` is API 30; below that the deprecated
 * `getInstallerPackageName` is the only source, and it may return null or throw
 * `IllegalArgumentException` when no installer is recorded. Both are wrapped so
 * the startup update check degrades to [UpdateInstallSource.UNKNOWN] instead of
 * crashing.
 */
class AndroidUpdateInstallSourceReader(
    private val context: Context,
) : UpdateInstallSourceReader {
    override fun current(): UpdateInstallSource = classify(readInstallingPackage())

    private fun readInstallingPackage(): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installingPackageFrom(context.packageManager.getInstallSourceInfo(context.packageName))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

    @Suppress("NewApi") // Caller gates on Build.VERSION.SDK_INT >= R; InstallSourceInfo is API 30.
    private fun installingPackageFrom(info: InstallSourceInfo): String? = info.installingPackageName

    companion object {
        private const val PLAY_STORE = "com.android.vending"

        /**
         * Pure classification, so the store/sideload/unknown split is unit-testable
         * without a `PackageManager`.
         */
        fun classify(installingPackageName: String?): UpdateInstallSource =
            when {
                installingPackageName == PLAY_STORE -> UpdateInstallSource.STORE
                installingPackageName == null -> UpdateInstallSource.UNKNOWN
                else -> UpdateInstallSource.SIDELOAD
            }
    }
}
