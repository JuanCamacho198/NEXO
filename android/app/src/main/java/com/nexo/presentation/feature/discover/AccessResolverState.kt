package com.nexo.presentation.feature.discover

import com.nexo.domain.access.LegalAccess

/**
 * UI state of the "Dónde leerlo" access section (U5).
 *
 * Pure-JVM sealed state so the full matrix (loading / loaded / empty /
 * error / offline / consent-gated) is unit-testable without a Compose
 * harness; [mapAccessState] is the single mapping rule used by
 * [DiscoverViewModel] and covered by `AccessResolverStateTest`.
 */
sealed interface AccessResolverState {
    /** Detail fetch in flight — the section renders skeleton rows. */
    data object Loading : AccessResolverState

    /** Resolved legal options for the open book (may still be action-less). */
    data class Loaded(
        val access: LegalAccess,
    ) : AccessResolverState

    /** Resolved with zero openable options — "sin coincidencias legales" copy. */
    data object Empty : AccessResolverState

    /** Detail fetch failed (non-connectivity) — error copy + retry. */
    data object Error : AccessResolverState

    /** Pre-emptive connectivity failure — offline copy + retry. */
    data object Offline : AccessResolverState

    /**
     * The open book belongs to an addon source whose capability disclosure
     * was not consented yet: no resolve ran (zero I/O), the section prompts
     * for consent instead of rendering options.
     */
    data class ConsentRequired(
        val addonId: String,
    ) : AccessResolverState
}

/**
 * Single mapping rule for [AccessResolverState].
 *
 * Precedence: offline first (never attempt), then the consent gate for
 * addon books (fail-closed: no consent ⇒ no resolve, no network), then
 * fetch failure, then the empty/loaded split on openable options. An
 * option counts as openable when its URL is non-blank; the in-app
 * download counts when [LegalAccess.canDownloadInApp] is true.
 */
fun mapAccessState(
    isOnline: Boolean,
    consentRequiredAddonId: String?,
    hasConsent: Boolean,
    access: LegalAccess?,
    failed: Boolean,
): AccessResolverState {
    if (!isOnline) return AccessResolverState.Offline
    if (consentRequiredAddonId != null && !hasConsent) {
        return AccessResolverState.ConsentRequired(consentRequiredAddonId)
    }
    if (failed || access == null) return AccessResolverState.Error
    val openable =
        access.options.count { it.url.isNotBlank() } +
            if (access.canDownloadInApp) HAS_DOWNLOAD_BONUS else NO_DOWNLOAD_BONUS
    return if (openable > NO_OPTIONS) {
        AccessResolverState.Loaded(access)
    } else {
        AccessResolverState.Empty
    }
}

/** Openable-option count above this value renders the loaded section. */
private const val NO_OPTIONS = 0

/** In-app download bonus counted as one openable option. */
private const val HAS_DOWNLOAD_BONUS = 1

/** No in-app download: no bonus option. */
private const val NO_DOWNLOAD_BONUS = 0
