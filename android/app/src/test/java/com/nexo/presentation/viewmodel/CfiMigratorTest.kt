package com.nexo.presentation.viewmodel

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Link

class CfiMigratorTest {
    @Test
    fun parsePreciseCfi_readsSpinePathAndTextOffset() {
        val parsed = CfiMigrator.parsePreciseCfi("epubcfi(/6/3!/4/2,/1:47,/1:61)")

        requireNotNull(parsed)
        assertEquals(3, parsed.spineIndex)
        assertEquals(listOf(4, 2), parsed.localPath)
        assertEquals(47, parsed.textOffset)
    }

    @Test
    fun progressionFor_usesRealTextOffset_notChapterStart() {
        val progression =
            CfiMigrator.progressionFor(
                CfiMigrator.TextMetric(charOffset = 47, chapterChars = 200),
            )

        assertEquals(0.235, progression!!, 0.0001)
        assertTrue(progression > 0.0)
    }

    @Test
    fun preciseCfiToLocator_returnsNullWithoutTextMetric_insteadOfChapterStart() {
        val link = mockk<Link>(relaxed = true)

        val locator =
            CfiMigrator.preciseCfiToLocator(
                "epubcfi(/6/1!/4/2,/1:4,/1:8)",
                listOf(link),
            ) { _, _ -> null }

        assertNull(locator)
    }
}
