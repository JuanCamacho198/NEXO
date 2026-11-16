package com.nexo.ui.components.atoms

import com.nexo.debug.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression for Sentry NEXO-ANDROID-H (543 events / 8 users): a book with no
 * cover reaches `AsyncImagePainter.State.Error` by design — a null `coverPath`
 * collapses the ImageRequest data to `null` and Coil resolves it through
 * `.error(R.drawable.cover_error)`. That is the normal no-cover state, so it
 * must NOT be reported at ERROR level (it flooded Sentry as a fake crash).
 *
 * What is pinned here is the pure severity decision behind
 * [CoverThumbnailLoadFailure]: absent cover => WARN, real failure => ERROR.
 */
class CoverThumbnailLoadFailureTest {
    @Before
    fun setUp() {
        DebugLog.clear()
        DebugLog.setMinLevel(DebugLog.Level.DEBUG)
    }

    @After
    fun tearDown() {
        DebugLog.clear()
        DebugLog.resetForTest()
    }

    @Test
    fun `null cover path reports at WARN not ERROR`() {
        reportCoverLoadFailure(null)

        assertEquals(
            "A book without a cover must not be reported at ERROR level (it floods Sentry)",
            DebugLog.Level.WARN,
            DebugLog.events.value.single().level,
        )
        assertTrue(
            "No ERROR event may be produced for a book with no cover",
            DebugLog.errorEvents.value.isEmpty(),
        )
    }

    @Test
    fun `blank cover path reports at WARN not ERROR`() {
        reportCoverLoadFailure("   ")

        assertEquals(
            "A blank cover path is the same normal state as null",
            DebugLog.Level.WARN,
            DebugLog.events.value.single().level,
        )
        assertTrue(
            "No ERROR event may be produced for a blank cover path",
            DebugLog.errorEvents.value.isEmpty(),
        )
    }

    @Test
    fun `real cover path that fails to load still reports at ERROR`() {
        reportCoverLoadFailure("/tmp/nexo-missing-cover.png")

        assertEquals(
            "A genuine load failure must keep its ERROR severity so Sentry still sees it",
            DebugLog.Level.ERROR,
            DebugLog.events.value.single().level,
        )
        assertEquals(
            "The ERROR event must reach the error ring that Sentry egress reads",
            DebugLog.Level.ERROR,
            DebugLog.errorEvents.value.single().level,
        )
    }

    @Test
    fun `warn and error messages are distinguishable in Sentry`() {
        reportCoverLoadFailure(null)
        val absent = DebugLog.events.value.single().message
        DebugLog.clear()

        reportCoverLoadFailure("/tmp/nexo-missing-cover.png")
        val failed = DebugLog.events.value.single().message

        assertTrue("The absent-cover message must be distinct from the failure message", absent != failed)
        assertFalse(
            "The absent-cover message must not read as a load failure",
            absent.contains("failed for"),
        )
    }
}
