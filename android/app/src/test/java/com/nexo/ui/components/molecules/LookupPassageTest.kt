package com.nexo.ui.components.molecules

import com.nexo.domain.event.NoticeTone
import com.nexo.ui.components.molecules.reader.noticeDurationMs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupPassageTest {
    @Test
    fun `a word or a short expression is set as a headword`() {
        assertFalse(isPassage("descontento"))
        assertFalse(isPassage("  aspecto "))
        assertFalse(isPassage("de vez en cuando"))
    }

    @Test
    fun `a sentence or a long selection is quoted as a passage`() {
        assertTrue(isPassage("Hoy trabajé mucho en la oficina."))
        assertTrue(isPassage("anticonstitucionalmente-desproporcionadamente"))
    }

    @Test
    fun `an error notice stays longer than a confirmation`() {
        assertTrue(noticeDurationMs(NoticeTone.ERROR) > noticeDurationMs(NoticeTone.SUCCESS))
        assertTrue(noticeDurationMs(NoticeTone.SUCCESS) in 1_500L..3_000L)
    }
}
