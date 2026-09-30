package com.nexo.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nexo.domain.model.AuthSession
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
) {
    LaunchedEffect(session?.userId, session?.photoUrl) {
        homeViewModel.setActiveSession(session)
        getStatisticsUseCase.setUserId(session?.userId)
        readerViewModel.lifecycleHolder.setActiveUserId(session?.userId.orEmpty())
    }
}
