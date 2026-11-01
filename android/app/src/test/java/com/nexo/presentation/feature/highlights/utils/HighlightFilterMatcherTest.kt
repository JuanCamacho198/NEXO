package com.nexo.presentation.feature.highlights.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightFilterMatcherTest {
    @Test
    fun `palette hex matches its own filter name`() {
        assertTrue(matchesHighlightFilter("#FACC15", "YELLOW"))
        assertTrue(matchesHighlightFilter("#3B82F6", "BLUE"))
    }

    @Test
    fun `palette hex does not match a different filter name`() {
        assertFalse(matchesHighlightFilter("#FACC15", "BLUE"))
    }

    @Test
    fun `null filter matches every highlight`() {
        assertTrue(matchesHighlightFilter("#FACC15", null))
        assertTrue(matchesHighlightFilter("#3B82F6", null))
        assertTrue(matchesHighlightFilter("", null))
        assertTrue(matchesHighlightFilter("no-hex", null))
    }

    @Test
    fun `filter name is case-insensitive`() {
        assertTrue(matchesHighlightFilter("#FACC15", "yellow"))
    }

    @Test
    fun `legacy hex matches via nearest colour`() {
        // Old PINK palette value; nearest entry by RGB distance is RED.
        assertTrue(matchesHighlightFilter("#F9A8D4", "RED"))
        // Old PURPLE palette value; nearest entry by RGB distance is BLUE.
        assertTrue(matchesHighlightFilter("#D8B4FE", "BLUE"))
    }

    @Test
    fun `legacy hex does not match a distant colour`() {
        // #F9A8D4 is closest to RED, not BLUE.
        assertFalse(matchesHighlightFilter("#F9A8D4", "BLUE"))
    }

    @Test
    fun `unparseable colour never matches a filter and never crashes`() {
        assertFalse(matchesHighlightFilter("", "YELLOW"))
        assertFalse(matchesHighlightFilter("no-hex", "YELLOW"))
        assertFalse(matchesHighlightFilter("#FACC1", "YELLOW"))
    }

    @Test
    fun `unknown filter name matches nothing`() {
        assertFalse(matchesHighlightFilter("#FACC15", "MAGENTA"))
    }
}
