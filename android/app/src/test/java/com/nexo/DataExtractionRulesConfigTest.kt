package com.nexo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `allowBackup="false"` only documents intent on API 31+: the cloud-backup and
 * device-transfer paths are configured by `android:dataExtractionRules`, and
 * leaving it unset made the transfer path undefined instead of denied.
 *
 * Both opt-outs must be explicit, and `allowBackup` / `fullBackupContent` must
 * not be relaxed to compensate.
 */
class DataExtractionRulesConfigTest {
    @Test
    fun `manifest references the data extraction rules resource`() {
        val manifest = readMainFile("AndroidManifest.xml")

        assertTrue(
            "application must reference @xml/data_extraction_rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "allowBackup=false must stay (L3 does not relax it)",
            manifest.contains("android:allowBackup=\"false\""),
        )
        assertTrue(
            "fullBackupContent=false must stay (L3 does not relax it)",
            manifest.contains("android:fullBackupContent=\"false\""),
        )
    }

    @Test
    fun `extraction rules deny both cloud backup and device transfer`() {
        val rules = readMainFile("res/xml/data_extraction_rules.xml")

        assertTrue("cloud-backup must be denied", rules.contains("<cloud-backup>"))
        assertTrue("device-transfer must be denied", rules.contains("<device-transfer>"))
        assertEquals(
            "exactly one cloud-backup and one device-transfer section",
            1,
            Regex("<cloud-backup>").findAll(rules).count(),
        )
        assertEquals(1, Regex("<device-transfer>").findAll(rules).count())
        assertEquals(
            "both sections must deny root, file, database and sharedpref",
            8,
            Regex("<exclude\\s+domain=\"(root|file|database|sharedpref)\"\\s+path=\"\\.\"\\s*/>").findAll(rules).count(),
        )
        assertTrue(
            "no include-list may re-open a path",
            !rules.contains("<include "),
        )
    }

    private fun readMainFile(relative: String): String {
        val candidates =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .take(5)
                .flatMap { dir ->
                    sequenceOf(
                        File(dir, "src/main/$relative"),
                        File(dir, "app/src/main/$relative"),
                    )
                }
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("$relative not found from ${File("").absolutePath}", file)
        return file!!.readText()
    }
}
