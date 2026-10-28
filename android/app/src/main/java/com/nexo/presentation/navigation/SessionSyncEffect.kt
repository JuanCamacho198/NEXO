package com.nexo.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nexo.domain.model.AuthSession
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.presentation.viewmodel.HomeViewModel
import com.nexo.presentation.viewmodel.ReaderViewModel

/**
 * Reactively pushes the restored/later auth session into the cached VMs.
 *
 * Mirrors the host's verbatim LaunchedEffect:
 * ```
 * LaunchedEffect(authState.currentSession?.userId, authState.currentSession?.photoUrl) {
 *   homeVM.setActiveSession(session)
 *   getStatisticsUseCase.setUserId(session?.userId)
 *   readerVM.setActiveUserId(session?.userId.orEmpty())
 * }
 * ```
 * Also re-scopes daily stats/streak and recorded reading sessions to the user
 * (REQ-reading-sessions-sync-6, REQ-streak-widget-1).
 */
@Composable
fun SessionSyncEffect(
    session: AuthSession?,
    homeViewModel: HomeViewModel,
    getStatisticsUseCase: GetStatisticsUseCase,
    readerViewModel: ReaderViewModel,
    activeUserProvider: ActiveUserProvider,
) {
    LaunchedEffect(session?.userId, session?.photoUrl) {
        homeViewModel.setActiveSession(session)
        getStatisticsUseCase.setUserId(session?.userId)
        readerViewModel.lifecycleHolder.setActiveUserId(session?.userId.orEmpty())
        // Published last so every holder above sees the same owner. This is the
        // only writer, and it is the sign-out path too: a null session clears
        // the provider, so no stale id can leak into the next account's
        // queries. `set` also drops a `local-*` id (decision D5), so the
        // anonymous path resolves to "no owner" and persists nothing.
        activeUserProvider.set(session?.userId)
    }
}
