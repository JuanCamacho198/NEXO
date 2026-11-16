package com.nexo.data.session

import com.nexo.domain.model.AuthSession

class InMemorySessionStore : SessionStore {
    @Volatile
    private var cached: AuthSession? = null

    override fun read(): AuthSession? = cached

    override fun write(session: AuthSession) {
        cached = session
    }

    override fun clear() {
        cached = null
    }
}
