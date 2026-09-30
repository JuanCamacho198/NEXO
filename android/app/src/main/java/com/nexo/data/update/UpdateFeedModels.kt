package com.nexo.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateAsset(
    val url: String = "",
    val abi: String = "universal",
    val size: Long = 0L,
)

@Serializable
data class AndroidUpdateFeed(
    val version: String,
    val versionCode: Int,
    val notes: String = "",
    @SerialName("pubDate")
    val pubDate: String = "",
    val channel: String = "stable",
    val assets: List<UpdateAsset> = emptyList(),
)
