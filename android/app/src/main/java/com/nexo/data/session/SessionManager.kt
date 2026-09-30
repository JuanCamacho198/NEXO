package com.nexo.data.session

import com.nexo.domain.model.AuthSession

interface SessionManager {
    suspend fun restoreSession(): Result<AuthSession?>

    suspend fun getCurrentSession(): Result<AuthSession?>

    suspend fun ensureFreshSession(): Result<AuthSession>

    suspend fun signOutAll(): Result<Unit>

    suspend fun setCurrentSession(session: AuthSession?): Result<Unit>
}
