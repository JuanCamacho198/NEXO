package com.nexo.domain.model

data class AuthSession(
    val userId: String,
    val email: String?,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val providerToken: String? = null,
    val provider: String? = null,
    val createdAt: String? = null,
)
