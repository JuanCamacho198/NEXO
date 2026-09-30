package com.nexo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStateTest {
    @Test
    fun resolveReadingState_usesCanonicalPrecedence() {
        assertEquals(ReadingState.TO_READ, resolveReadingState(null, 0f))
        assertEquals(ReadingState.READING, resolveReadingState(ReadingState.READING, 0f))
        assertEquals(ReadingState.READING, resolveReadingState(null, 25f))
        assertEquals(ReadingState.COMPLETED, resolveReadingState(ReadingState.TO_READ, 100f))
        assertEquals(ReadingState.COMPLETED, resolveReadingState(ReadingState.COMPLETED, 0f))
    }
}
