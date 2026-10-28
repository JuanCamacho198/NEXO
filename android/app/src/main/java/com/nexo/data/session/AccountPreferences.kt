package com.nexo.data.session

import android.content.Context
import android.content.SharedPreferences
import com.nexo.domain.session.ActiveUserProvider

/**
 * Resolves an account-scoped preferences file (T10 of
 * `aislamiento-datos-por-cuenta`).
 *
 * ## The leak this closes
 *
 * Personal preferences used to live in a single global
 * `SharedPreferences` file with fixed keys. Two accounts signing in on the
 * same device therefore shared them, and — the security defect — account B
 * inherited account A's Google Drive OAuth tokens.
 *
 * ## Chosen strategy: per-account FILE NAME, not per-account keys
 *
 * `{baseName}__{userId}` instead of `{userId}.fontSize` keys inside one file.
 * This was chosen because:
 *
 * - **The encrypted variant reuses the existing construction verbatim.**
 *   [androidx.security.crypto.EncryptedSharedPreferences.create] is keyed by file
 *   name, so a per-account file is the same
 *   [androidx.security.crypto.MasterKey] + same cipher schemes with a different
 *   name. Per-key scoping inside one encrypted file would leave every account's
 *   ciphertext under one Keystore-owned file, which nothing else in this
 *   codebase does and which buys nothing.
 * - **Sign-out hygiene is a file operation.** Dropping an account is deleting
 *   one file; there is no partial-key sweep to get wrong.
 * - **It mirrors the filesystem decision D2/D8.** `AccountStorageLayout`
 *   namespaces on-disk data as `{tipo}/{userId}/`; preferences now namespace the
 *   same way, so "is this account's data isolated?" has one answer everywhere.
 *
 * There is no migration of pre-existing values: decision D1 (the app has no
 * production users) makes a clean break correct, so any legacy global file is
 * simply not read anymore.
 *
 * ## No owner means no file
 *
 * [open] returns `null` when [ActiveUserProvider] reports no owner — signed out,
 * or the anonymous/local session of decision D5. Callers then fall back to
 * defaults and skip every write, so the anonymous path reads and writes nothing,
 * exactly like the DAO and filesystem layers.
 */
object AccountPreferences {
    /** Owner segment separator; `__` cannot appear in a Supabase UUID. */
    private const val SEPARATOR = "__"

    /**
     * The account-scoped preferences file name for [baseName], or `null` when no
     * account is active (signed out or anonymous — decision D5).
     */
    fun fileNameFor(
        baseName: String,
        activeUser: ActiveUserProvider,
    ): String? = fileNameForOwner(baseName, activeUser.currentUserId())

    /**
     * The account-scoped file name for an explicit [userId], or `null` when it is
     * absent or an ephemeral anonymous/local id.
     *
     * Use this only where the owner is known from an authoritative source other
     * than [ActiveUserProvider] — today, the just-published auth session at
     * sign-in. Everywhere else, read the provider.
     */
    fun fileNameForOwner(
        baseName: String,
        userId: String?,
    ): String? =
        userId
            ?.takeIf { it.isNotBlank() && !ActiveUserProvider.isLocalUserId(it) }
            ?.let { owner -> "$baseName$SEPARATOR$owner" }

    /**
     * Opens the plain (unencrypted) per-account preferences file, or `null` when
     * no account is active. Only for non-secret personal state.
     */
    fun open(
        context: Context,
        baseName: String,
        activeUser: ActiveUserProvider,
    ): SharedPreferences? {
        val name = fileNameFor(baseName, activeUser) ?: return null
        return context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}
