package com.nexo.domain.update

sealed interface UpdateFeedFetch {
    data class Found(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val channel: String,
        val assetUrl: String,
    ) : UpdateFeedFetch

    data class Failed(
        val kind: UpdateFeedFailure,
    ) : UpdateFeedFetch
}

enum class UpdateFeedFailure {
    DISABLED,
    UNREACHABLE,
    MALFORMED,
}

interface UpdateFeedFetcher {
    val isConfigured: Boolean

    suspend fun fetch(): UpdateFeedFetch
}

interface UpdateSuppressionStore {
    fun isSuppressed(
        version: String,
        nowEpochMs: Long,
    ): Boolean

    fun remindLater(
        version: String,
        nowEpochMs: Long,
    )
}

interface UpdateNetworkGate {
    fun isOnline(): Boolean

    fun isMetered(): Boolean
}
