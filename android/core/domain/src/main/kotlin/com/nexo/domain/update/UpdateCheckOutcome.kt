package com.nexo.domain.update

enum class UpdateErrorKind {
    UNREACHABLE,
    MALFORMED,
    OFFLINE,

    /** The running build was not sideloaded, so in-app self-install is refused. */
    MANAGED_INSTALL,
}

data class UpdateCandidate(
    val version: String,
    val versionCode: Int,
    val notes: String,
    val assetUrl: String,
)

sealed interface UpdateCheckOutcome {
    data object Disabled : UpdateCheckOutcome

    data object Deferred : UpdateCheckOutcome

    data object UpToDate : UpdateCheckOutcome

    data class Available(
        val candidate: UpdateCandidate,
    ) : UpdateCheckOutcome

    data class Error(
        val kind: UpdateErrorKind,
    ) : UpdateCheckOutcome
}
