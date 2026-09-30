package com.nexo.domain.model

data class DeviceInfo(
    val hardwareId: String,
    val name: String,
    val os: String,
    val type: String = "mobile",
)
