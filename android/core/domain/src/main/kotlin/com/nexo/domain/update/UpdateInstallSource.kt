package com.nexo.domain.update

/**
 * Where the currently running build came from, as far as the platform can tell.
 *
 * The app is distributed as a GitHub Releases APK that users sideload, so
 * self-update is a real feature. It must never replace a build that a store
 * installed: that build is managed by the store, and a feed download would
 * silently bypass it.
 */
enum class UpdateInstallSource {
    /** Installed by a store (`com.android.vending` for Play). Self-update is refused. */
    STORE,

    /** Installed from an APK the user opened by hand, or by another non-store package. */
    SIDELOAD,

    /** The platform could not report an installer; treated as a store build. */
    UNKNOWN,
}

interface UpdateInstallSourceReader {
    fun current(): UpdateInstallSource
}

/**
 * Decides whether the in-app updater may run at all.
 *
 * Returns `false` with no exception for any non-sideload source: a build the
 * user did not install from a file must not be replaced behind the store's back.
 */
class UpdateInstallerGuard(
    private val installSource: UpdateInstallSourceReader,
) {
    fun allowsSelfInstall(): Boolean = installSource.current() == UpdateInstallSource.SIDELOAD
}
