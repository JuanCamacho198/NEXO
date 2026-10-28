package com.nexo.domain.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single access point for the active account id (T6 of
 * `aislamiento-datos-por-cuenta`).
 *
 * ## Who sets it
 *
 * Exactly one writer: [com.nexo.presentation.navigation.SessionSyncEffect],
 * which publishes the restored/later auth session once per `userId` change. The
 * provider is a process-wide singleton (built in `AppContainer`, provided by
 * `HiltSingletonsModule`), so every reader sees the same owner.
 *
 * ## When it changes
 *
 * On sign-in the id is set to the Supabase UUID. On sign-out the effect sets
 * `null`. Because the value is cleared on sign-out, a stale id from the previous
 * account can never leak into the next account's queries.
 *
 * ## Before it is set (pre-session)
 *
 * `null` means "no account is active". Nothing is owned by nobody: a signed-out
 * caller sees no local rows rather than every account's. [requireUserId]
 * therefore returns the empty string for a query that demands an owner, and no
 * persisted row can carry an empty owner, so the predicate matches nothing.
 *
 * ## The anonymous/local rule (decision D5)
 *
 * [com.nexo.data.repository.GoogleAuthRepository.signInLocally] mints a
 * `local-{UUID}` id **on every launch**, so anything persisted under it becomes
 * orphaned the moment the app restarts. The anonymous path therefore reads only:
 * it must never write to the local DB or the filesystem.
 *
 * That rule is enforced here rather than at each call site. [set] drops any
 * local id, so the provider reports "no owner" and every DAO query filtered by
 * the active user come back empty — the anonymous user reads from the cloud and
 * writes nothing locally. [isAnonymous] lets a caller branch explicitly when it
 * needs to (e.g. to hide a write affordance) instead of inferring it from an id
 * prefix.
 */
class ActiveUserProvider {
    private val _userId = MutableStateFlow<String?>(null)

    /** Current owner, or `null` when signed out or on the anonymous path. */
    val userId: StateFlow<String?> = _userId.asStateFlow()

    /**
     * Owner id for a query that requires one. Empty string when signed out or
     * anonymous, which matches no row because a persisted row always carries a
     * real id.
     */
    fun requireUserId(): String = _userId.value.orEmpty()

    /** Current owner, or `null` when signed out or anonymous. */
    fun currentUserId(): String? = _userId.value?.takeIf { it.isNotBlank() }

    /**
     * True when no persistent owner is active — either signed out or sitting on
     * the ephemeral anonymous session. Callers that would write must check this
     * instead of assuming [requireUserId] is a persistable owner.
     */
    fun isAnonymous(): Boolean = _userId.value.isNullOrBlank()

    /**
     * Sets the owner. A local/anonymous id ([isLocalUserId]) or a blank id
     * resolves to `null` — no owner — so the anonymous path cannot persist
     * (decision D5).
     */
    fun set(userId: String?) {
        val candidate = userId?.takeIf { it.isNotBlank() && !isLocalUserId(it) }
        _userId.value = candidate
    }

    /** Clears the owner. Called on sign-out so no stale id survives. */
    fun clear() = set(null)

    companion object {
        /**
         * The prefix [com.nexo.data.repository.GoogleAuthRepository.signInLocally]
         * uses for the throwaway, per-launch anonymous id.
         */
        const val LOCAL_USER_PREFIX: String = "local-"

        /** True when [userId] is an ephemeral anonymous/local id, never a real owner. */
        fun isLocalUserId(userId: String): Boolean = userId.startsWith(LOCAL_USER_PREFIX)
    }
}
