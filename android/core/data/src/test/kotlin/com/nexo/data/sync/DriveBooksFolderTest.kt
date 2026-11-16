package com.nexo.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveBooksFolderTest {
    @Test
    fun freshInstall_createsTheCanonicalFolderAndNeverConsultsTheGate() {
        assertEquals(DriveBooksFolder.Plan.Create, DriveBooksFolder.plan(canonicalId = null, legacyId = null, filenameVersion = 0))
        assertEquals(DriveBooksFolder.Plan.Create, DriveBooksFolder.plan(canonicalId = null, legacyId = null, filenameVersion = 0))
    }

    @Test
    fun legacyTree_withoutFilenameVersion_isBlocked() {
        assertFalse(DriveBooksFolder.shouldMigrate(0))
        assertEquals(
            DriveBooksFolder.Plan.Blocked("legacy-id"),
            DriveBooksFolder.plan(canonicalId = null, legacyId = "legacy-id", filenameVersion = 0),
        )
    }

    @Test
    fun legacyTree_withFilenameVersion_renamesInPlace() {
        assertTrue(DriveBooksFolder.shouldMigrate(1))
        assertEquals(
            DriveBooksFolder.Plan.Rename("legacy-id"),
            DriveBooksFolder.plan(canonicalId = null, legacyId = "legacy-id", filenameVersion = 1),
        )
    }

    @Test
    fun canonicalFolder_winsOverACaseSensitiveLegacyTwin() {
        assertEquals(
            DriveBooksFolder.Plan.Adopt("canonical-id"),
            DriveBooksFolder.plan(canonicalId = "canonical-id", legacyId = "legacy-id", filenameVersion = 0),
        )
    }

    @Test
    fun pickFolder_prefersCanonicalThenLegacyThenNull() {
        assertEquals("canonical-id", DriveBooksFolder.pickFolder("canonical-id", "legacy-id"))
        assertEquals("legacy-id", DriveBooksFolder.pickFolder(null, "legacy-id"))
        assertNull(DriveBooksFolder.pickFolder(null, null))
    }
}
