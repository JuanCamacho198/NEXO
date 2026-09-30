package com.nexo.presentation.viewmodel

/**
 * Source contract for the debug-only Performance screen. Real implementations
 * feed measured data; generated values are forbidden (perf-screen spec).
 */
interface PerformanceDataSource {
    fun generateTimings(): List<PerformanceTiming>

    suspend fun loadResources(): PerformanceResources

    fun loadSyncStatus(): PerformanceSyncStatus

    fun loadDiagnostics(): PerformanceDiagnostics
}
