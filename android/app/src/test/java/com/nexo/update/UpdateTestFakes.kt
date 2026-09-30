package com.nexo.update

import com.nexo.data.session.UpdatePrefs
import com.nexo.domain.update.UpdateFeedFetch
import com.nexo.domain.update.UpdateFeedFetcher
import com.nexo.domain.update.UpdateNetworkGate
import com.nexo.domain.update.UpdateSuppressionStore

internal class FakeFetcher(
    var result: UpdateFeedFetch,
    override val isConfigured: Boolean = true,
) : UpdateFeedFetcher {
    override suspend fun fetch(): UpdateFeedFetch = result
}

internal class FakeSuppression : UpdateSuppressionStore {
    val dismissed = mutableMapOf<String, Long>()
    var now: Long = 1_000L

    override fun isSuppressed(
        version: String,
        nowEpochMs: Long,
    ): Boolean {
        val at = dismissed[version] ?: return false
        return nowEpochMs - at < UpdatePrefs.REMIND_LATER_INTERVAL_MS
    }

    override fun remindLater(
        version: String,
        nowEpochMs: Long,
    ) {
        dismissed[version] = nowEpochMs
    }
}

internal class FakeGate(
    var online: Boolean = true,
    var metered: Boolean = false,
) : UpdateNetworkGate {
    override fun isOnline(): Boolean = online

    override fun isMetered(): Boolean = metered
}

internal fun foundFetch(
    version: String = "0.4.0",
    versionCode: Int = 400,
    channel: String = "stable",
): UpdateFeedFetch.Found =
    UpdateFeedFetch.Found(
        version = version,
        versionCode = versionCode,
        notes = "notes",
        channel = channel,
        assetUrl = "https://example.com/nexo-android-v0.4.0.apk",
    )
