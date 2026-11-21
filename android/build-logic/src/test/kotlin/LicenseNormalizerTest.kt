import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The POM `<name>` field is free text, so the inventory's grouping is only as
 * good as this mapping. Each case here is a string that is actually present in
 * this machine's Gradle cache, plus the boundary cases that decide where a
 * declaration lands when it is ambiguous.
 */
class LicenseNormalizerTest {
    private fun id(
        name: String?,
        url: String? = null,
    ): String = LicenseNormalizer.normalize(name, url).id

    @Test
    fun apacheVariantsNormaliseToOneIdentifier() {
        val variants =
            listOf(
                "The Apache Software License, Version 2.0",
                "Apache-2.0",
                "Apache License 2.0",
                "The Apache License, Version 2.0",
                "Apache License, Version 2.0",
                "Apache 2.0",
                "Apache 2",
                "Apache License v2.0",
                "APACHE LICENSE VERSION 2.0",
            )
        variants.forEach { name ->
            assertEquals(LicenseIds.APACHE_2_0, id(name), "raw name: $name")
        }
    }

    @Test
    fun mitVariantsNormaliseToOneIdentifier() {
        listOf("MIT", "MIT License", "The MIT License", "mit license").forEach { name ->
            assertEquals(LicenseIds.MIT, id(name), "raw name: $name")
        }
    }

    @Test
    fun bsdVariantsSplitByClauseCount() {
        listOf("BSD-3-Clause", "BSD-3-Clause license", "New BSD License", "BSD New license", "BSD style").forEach { name ->
            assertEquals(LicenseIds.BSD_3_CLAUSE, id(name), "raw name: $name")
        }
        listOf("BSD-2-Clause", "Simplified BSD License").forEach { name ->
            assertEquals(LicenseIds.BSD_2_CLAUSE, id(name), "raw name: $name")
        }
    }

    @Test
    fun eclipsePublicLicenseVersionsStayApart() {
        assertEquals(LicenseIds.EPL_2_0, id("Eclipse Public License v2.0"))
        assertEquals(LicenseIds.EPL_2_0, id("Eclipse Public License - v. 2.0"))
        assertEquals(LicenseIds.EPL_2_0, id("Eclipse Public License, Version 2.0"))
        assertEquals(LicenseIds.EPL_2_0, id("EPL-2.0"))
        assertEquals(LicenseIds.EPL_1_0, id("Eclipse Public License - v 1.0"))
        assertEquals(LicenseIds.EPL_1_0, id("Eclipse Public License 1.0"))
    }

    @Test
    fun eclipseDistributionLicenseIsNotThePublicLicense() {
        assertEquals(LicenseIds.EDL_1_0, id("Eclipse Distribution License - v 1.0"))
        assertEquals(LicenseIds.EDL_1_0, id("EDL 1.0"))
    }

    @Test
    fun apacheDraftWithAcknowledgmentIsNotApache2() {
        // The ASF describes its 2004 pre-release draft this way. Reporting it as
        // Apache-2.0 would attribute terms the library was never licensed under.
        assertEquals(
            LicenseIds.APACHE_BACKPORT,
            id("Similar to Apache License but with the acknowledgment clause removed"),
        )
    }

    @Test
    fun androidSdkLicenseIsItsOwnBucket() {
        assertEquals(LicenseIds.ANDROID_SDK, id("Android Software Development Kit License"))
    }

    @Test
    fun freeTextUnknownFallsToOther() {
        listOf("Bouncy Castle Licence", "Unicode-3.0", "Other", "Proprietary").forEach { name ->
            assertEquals(LicenseIds.OTHER, id(name), "raw name: $name")
        }
    }

    @Test
    fun missingDeclarationIsNotDeclaredAndIsNeverGuessed() {
        assertEquals(LicenseIds.NOT_DECLARED, id(null))
        assertEquals(LicenseIds.NOT_DECLARED, id(""))
        assertEquals(LicenseIds.NOT_DECLARED, id("   "))
        // No name and no URL: nothing to read, so nothing is claimed.
        assertEquals(LicenseIds.NOT_DECLARED, id(null, null))
    }

    @Test
    fun urlIsASecondSignalWhenTheNameSaysNothing() {
        assertEquals(LicenseIds.APACHE_2_0, id(null, "http://www.apache.org/licenses/LICENSE-2.0.txt"))
        assertEquals(LicenseIds.APACHE_2_0, id("", "https://api.github.com/licenses/apache-2.0"))
        assertEquals(LicenseIds.MIT, id("", "https://opensource.org/licenses/MIT"))
    }

    @Test
    fun rawDeclarationIsPreservedForAudit() {
        val resolution = LicenseNormalizer.normalize("The Apache Software License, Version 2.0", "http://example.test")
        assertEquals("The Apache Software License, Version 2.0", resolution.rawName)
        assertEquals("http://example.test", resolution.rawUrl)
    }
}
