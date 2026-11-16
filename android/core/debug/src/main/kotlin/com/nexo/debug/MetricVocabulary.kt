package com.nexo.debug

/**
 * Kotlin mirror of the shared metric vocabulary defined in TS
 * (`desktop/src/lib/shared/logger/metricTypes.ts` → `SHARED_METRIC_VOCABULARY`).
 *
 * Drift between the two lists is caught by the desktop lockstep test
 * (`metricVocabulary.lockstep.test.ts`). Do not edit one side without the other.
 */
object MetricVocabulary {
    val P0_METRIC_NAMES =
        listOf(
            "app_cold_start",
            "reader_open",
            "reader_ttfp_web",
            "reader_ttfp_native",
            "sync_flush",
            "outbox_depth",
            "book_import",
            "ipc_call",
        )

    const val PLATFORM_DESKTOP = "desktop"
    const val PLATFORM_ANDROID = "android"
    const val SOURCE_READER = "reader"
    const val SOURCE_APP_SHELL = "app_shell"
    const val SOURCE_SYNC = "sync"
    const val SOURCE_IMPORT = "import"
    const val ENGINE_READIUM = "readium"
    const val FORMAT_EPUB = "epub"
    const val FORMAT_PDF = "pdf"
}
