package com.nextpage.domain.update

class CheckForUpdatesUseCase(
    private val fetcher: UpdateFeedFetcher,
    private val suppression: UpdateSuppressionStore,
    private val networkGate: UpdateNetworkGate,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun isFeedEnabled(): Boolean = fetcher.isConfigured

    suspend fun check(
        manual: Boolean,
        installedVersionCode: Int,
    ): UpdateCheckOutcome {
        if (!manual && !networkGate.isOnline()) return UpdateCheckOutcome.Deferred
        if (!manual && networkGate.isMetered()) return UpdateCheckOutcome.Deferred
        return when (val fetched = fetcher.fetch()) {
            is UpdateFeedFetch.Failed ->
                when (fetched.kind) {
                    UpdateFeedFailure.DISABLED -> UpdateCheckOutcome.Disabled
                    UpdateFeedFailure.MALFORMED -> updateOrDefer(manual, UpdateErrorKind.MALFORMED)
                    UpdateFeedFailure.UNREACHABLE -> unreachableOrDefer(manual)
                }
            is UpdateFeedFetch.Found -> resolveFound(fetched, manual, installedVersionCode)
        }
    }

    private fun updateOrDefer(
        manual: Boolean,
        kind: UpdateErrorKind,
    ): UpdateCheckOutcome = if (manual) UpdateCheckOutcome.Error(kind) else UpdateCheckOutcome.Deferred

    private fun unreachableOrDefer(manual: Boolean): UpdateCheckOutcome {
        if (!manual) return UpdateCheckOutcome.Deferred
        val kind =
            if (networkGate.isOnline()) UpdateErrorKind.UNREACHABLE else UpdateErrorKind.OFFLINE
        return UpdateCheckOutcome.Error(kind)
    }

    private fun resolveFound(
        fetched: UpdateFeedFetch.Found,
        manual: Boolean,
        installedVersionCode: Int,
    ): UpdateCheckOutcome {
        if (fetched.channel != STABLE_CHANNEL) return UpdateCheckOutcome.UpToDate
        if (fetched.versionCode <= installedVersionCode) return UpdateCheckOutcome.UpToDate
        if (!manual && suppression.isSuppressed(fetched.version, nowEpochMs())) {
            return UpdateCheckOutcome.Deferred
        }
        return UpdateCheckOutcome.Available(
            UpdateCandidate(
                version = fetched.version,
                versionCode = fetched.versionCode,
                notes = fetched.notes,
                assetUrl = fetched.assetUrl,
            ),
        )
    }

    private companion object {
        const val STABLE_CHANNEL = "stable"
    }
}
