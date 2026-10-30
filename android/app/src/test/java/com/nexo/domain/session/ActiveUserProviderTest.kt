package com.nexo.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6/T7: the active-user contract. Who sets it, when it clears, and what the
 * anonymous/local path resolves to.
 */
class ActiveUserProviderTest {
    private val provider = ActiveUserProvider()

    @Test
    fun preSession_reportsNoOwner() {
        assertNull(provider.currentUserId())
        assertEquals("", provider.requireUserId())
        assertTrue(provider.isAnonymous())
    }

    @Test
    fun setRealId_publishesTheOwner() {
        provider.set("user-a")

        assertEquals("user-a", provider.currentUserId())
        assertEquals("user-a", provider.requireUserId())
        assertFalse(provider.isAnonymous())
    }

    @Test
    fun signOut_clearsTheOwner_soNoStaleIdCanLeak() {
        provider.set("user-a")
        provider.set(null)

        assertNull(provider.currentUserId())
        assertEquals("", provider.requireUserId())

        // The next account starts from "no owner", never from user-a.
        provider.set("user-b")
        assertEquals("user-b", provider.currentUserId())
    }

    @Test
    fun clear_isEquivalentToSettingNull() {
        provider.set("user-a")
        provider.clear()

        assertNull(provider.currentUserId())
        assertTrue(provider.isAnonymous())
    }

    @Test
    fun blankId_isTreatedAsNoOwner() {
        provider.set("   ")

        assertNull(provider.currentUserId())
    }

    // ── T7 / decision D5: the anonymous path owns nothing ───────────────

    @Test
    fun anonymousId_isDropped_soNoLocalRowCanBeWritten() {
        provider.set("local-9f1c4a2e-0000-4a2e-8e2a-000000000000")

        assertNull(provider.currentUserId())
        assertEquals("", provider.requireUserId())
        assertTrue(provider.isAnonymous())
    }

    @Test
    fun localPrefix_isRecognized() {
        assertTrue(ActiveUserProvider.isLocalUserId("local-abc"))
        assertFalse(ActiveUserProvider.isLocalUserId("a4f0-uuid"))
        assertFalse(ActiveUserProvider.isLocalUserId(""))
    }

    @Test
    fun switchingFromAnonymousToRealAccount_startsClean() {
        provider.set("local-abc")
        provider.set("user-a")

        assertEquals("user-a", provider.currentUserId())
    }
}
