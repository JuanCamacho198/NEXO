package com.nexo.presentation.feature.discover

import androidx.annotation.StringRes

/**
 * Resource ids the host supplies to this feature's components.
 *
 * [AddonCapabilityBadges] and [AddonTrustNote] live here but are also rendered by
 * two `:app` screens (addon management and addon capability detail). A library
 * module cannot read `:app`'s `R` class, so `:app` passes the ids it owns and the
 * feature resolves them with its own `stringResource` call instead of the copy
 * being duplicated into two resource tables.
 */
data class DiscoverStringRes(
    /** Capability chips header. */
    @param:StringRes val capabilitiesTitle: Int = 0,
    /** Consent trust note under the capability chips. */
    @param:StringRes val consentTrustNote: Int = 0,
    /** "Read legal notice" link text. */
    @param:StringRes val viewPolicyLabel: Int = 0,
    /** One grouped rail-failure notice per [com.nexo.domain.discover.DiscoverRailGroup]. */
    val railGroupLabels: Map<com.nexo.domain.discover.DiscoverRailGroup, Int> = emptyMap(),
)
