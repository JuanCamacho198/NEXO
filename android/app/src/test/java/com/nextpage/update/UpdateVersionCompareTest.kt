package com.nextpage.update

import com.nextpage.data.update.isUpdateAvailable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionCompareTest {
    @Test
    fun newerFeedVersionCode_offersUpdate() {
        assertTrue(isUpdateAvailable(feedVersionCode = 400, installedVersionCode = 300))
    }

    @Test
    fun equalVersionCode_offersNothing() {
        assertFalse(isUpdateAvailable(feedVersionCode = 300, installedVersionCode = 300))
    }

    @Test
    fun olderFeedVersionCode_offersNothing() {
        assertFalse(isUpdateAvailable(feedVersionCode = 299, installedVersionCode = 300))
    }
}
