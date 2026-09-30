package com.nexo.domain.error

enum class ErrorCategory {
    CONFIG_ERROR,
    WIRING_ERROR,
    NETWORK,
    AUTH,
    VALIDATION,
    NOT_FOUND,
    STORAGE,
    UNKNOWN,
}

data class AppError(
    val category: ErrorCategory,
    val code: String,
    override val message: String,
    val component: String,
) : RuntimeException(message)
