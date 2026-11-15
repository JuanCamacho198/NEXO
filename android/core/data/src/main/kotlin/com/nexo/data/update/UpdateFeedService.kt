package com.nexo.data.update

import com.nexo.domain.update.UpdateFeedFailure
import com.nexo.domain.update.UpdateFeedFetch
import com.nexo.domain.update.UpdateFeedFetcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class UpdateFeedService(
    private val client: HttpClient,
    private val feedUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : UpdateFeedFetcher {
    override val isConfigured: Boolean get() = feedUrl.trim().isNotEmpty()

    override suspend fun fetch(): UpdateFeedFetch {
        val url = feedUrl.trim()
        if (url.isEmpty()) return UpdateFeedFetch.Failed(UpdateFeedFailure.DISABLED)
        return try {
            val response =
                client.get(url) {
                    header(HttpHeaders.Accept, "application/json")
                }
            if (!response.status.isSuccess()) {
                return UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE)
            }
            parse(response.bodyAsText())
        } catch (err: Exception) {
            if (err is UpdateFeedParseException) {
                UpdateFeedFetch.Failed(UpdateFeedFailure.MALFORMED)
            } else {
                UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE)
            }
        }
    }

    private fun parse(body: String): UpdateFeedFetch {
        val feed =
            try {
                json.decodeFromString<AndroidUpdateFeed>(body)
            } catch (err: IllegalArgumentException) {
                throw UpdateFeedParseException(err)
            }
        val assetUrl =
            feed.assets
                .firstOrNull()
                ?.url
                .orEmpty()
        if (feed.version.isBlank() || assetUrl.isBlank()) {
            throw UpdateFeedParseException(null)
        }
        return UpdateFeedFetch.Found(
            version = feed.version,
            versionCode = feed.versionCode,
            notes = feed.notes,
            channel = feed.channel,
            assetUrl = assetUrl,
        )
    }

    private class UpdateFeedParseException(
        cause: Throwable?,
    ) : Exception(cause)
}
