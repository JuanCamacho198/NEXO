package com.nexo.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("hardware_id") val hardwareId: String = "",
    val name: String = "",
    val os: String = "",
    val type: String = "mobile",
    @SerialName("last_active") val lastActive: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)
