package com.nexo.data.session

import com.nexo.domain.model.AuthSession

interface SessionStore {
    fun read(): AuthSession?

    fun write(session: AuthSession)

    fun clear()
}
