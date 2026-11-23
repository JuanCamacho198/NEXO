import java.util.Locale

/**
 * The licence identifiers this build normalises to.
 *
 * POM `<name>` values are free text: "The Apache Software License, Version 2.0",
 * "Apache-2.0" and "Apache License 2.0" all name the same licence. Grouping the
 * inventory by that raw string would produce dozens of Apache groups, so every
 * declared name is collapsed to one of these identifiers first.
 */
object LicenseIds {
    const val APACHE_2_0 = "Apache-2.0"
    const val MIT = "MIT"
    const val BSD_2_CLAUSE = "BSD-2-Clause"
    const val BSD_3_CLAUSE = "BSD-3-Clause"
    const val ISC = "ISC"
    const val EPL_1_0 = "EPL-1.0"
    const val EPL_2_0 = "EPL-2.0"
    const val EDL_1_0 = "EDL-1.0"
    const val MPL_1_1 = "MPL-1.1"
    const val MPL_2_0 = "MPL-2.0"
    const val CDDL_1_0 = "CDDL-1.0"
    const val GPL_2_0 = "GPL-2.0"
    const val LGPL_2_1 = "LGPL-2.1"
    const val ANDROID_SDK = "Android-SDK"
    const val APACHE_BACKPORT = "Apache-2.0-WITH-ACKNOWLEDGMENT"
    const val CC0_1_0 = "CC0-1.0"
    const val UNLICENSE = "Unlicense"

    /** A declared name this build cannot map to a canonical identifier. */
    const val OTHER = "OTHER"

    /** The POM declared no `<licenses>` block at all. Never guessed. */
    const val NOT_DECLARED = "NOT_DECLARED"
}

/** One canonical identifier plus the raw POM text it was derived from. */
data class LicenseResolution(
    val id: String,
    val rawName: String,
    val rawUrl: String,
)

/**
 * Normalises a POM licence declaration to a canonical SPDX-style identifier.
 *
 * The `<name>` field is the primary signal; `<url>` is a second, weaker one and
 * is only consulted when the name says nothing recognisable. A declaration with
 * neither yields [LicenseIds.OTHER] — a known-unknown — and an absent
 * `<licenses>` block yields [LicenseIds.NOT_DECLARED]. The two are kept apart on
 * purpose: "we could not classify what they declared" is a different statement
 * from "they declared nothing", and the screen says so.
 */
object LicenseNormalizer {
    fun normalize(
        rawName: String?,
        rawUrl: String?,
    ): LicenseResolution {
        val name = rawName?.trim().orEmpty()
        val url = rawUrl?.trim().orEmpty()
        return LicenseResolution(
            id = classify(name, url),
            rawName = name,
            rawUrl = url,
        )
    }

    private fun classify(
        name: String,
        url: String,
    ): String {
        if (name.isNotEmpty()) {
            return fromName(name.lowercase(Locale.ROOT).collapse())
        }
        if (url.isNotEmpty()) {
            return fromUrl(url.lowercase(Locale.ROOT))
        }
        return LicenseIds.NOT_DECLARED
    }

    private fun fromName(name: String): String =
        when {
            name.contains("mit") && !name.contains("apache") -> LicenseIds.MIT
            name.contains("expat") -> LicenseIds.MIT
            name.contains("bsd") -> fromBsdVariant(name)
            name.contains("isc") -> LicenseIds.ISC
            name.contains("unlicense") -> LicenseIds.UNLICENSE
            name.contains("cc0") || name.contains("creative commons zero") -> LicenseIds.CC0_1_0
            name.contains("eclipse distribution") || name.contains(" edl ") || name == "edl 1 0" ->
                LicenseIds.EDL_1_0
            name.contains("eclipse public") || name.contains(" epl") -> fromEclipsePublic(name)
            name.contains("mozilla public") || name.contains(" mpl") -> fromMozilla(name)
            name.contains("apache") -> fromApache(name)
            name.contains("cddl") || name.contains("common development and distribution") -> LicenseIds.CDDL_1_0
            name.contains("lesser general") || name.contains("lgpl") -> LicenseIds.LGPL_2_1
            name.contains("gnu general") || name.contains(" gpl") -> LicenseIds.GPL_2_0
            name.contains("android software development kit") -> LicenseIds.ANDROID_SDK
            else -> LicenseIds.OTHER
        }

    /**
     * Three-clause unless the text says 2-clause. "New BSD", "Revised" and a bare
     * "BSD" all name the three-clause form; the two-clause form is "Simplified".
     */
    private fun fromBsdVariant(name: String): String =
        when {
            name.contains("2 clause") || name.contains("simplified") || name.contains("freebsd") ->
                LicenseIds.BSD_2_CLAUSE
            else -> LicenseIds.BSD_3_CLAUSE
        }

    /**
     * The 1.0 wording is "Eclipse Public License - v 1.0"; the 2.0 wording is
     * "Eclipse Public License - v 2.0", "v. 2.0" or "v2.0". Only an explicit 1.0
     * maps to [LicenseIds.EPL_1_0] — the default is the current version, so an
     * unknown suffix never silently downgrades a licence.
     */
    private fun fromEclipsePublic(name: String): String = if (VERSION_1_0.containsMatchIn(name)) LicenseIds.EPL_1_0 else LicenseIds.EPL_2_0

    /** Matches "1.0" or "1 0" once punctuation is folded, but not "2.0". */
    private val VERSION_1_0 = Regex("(^| )1\\.0|(^| )1 0( |$)")

    private fun fromMozilla(name: String): String = if (VERSION_1_X.containsMatchIn(name)) LicenseIds.MPL_1_1 else LicenseIds.MPL_2_0

    /** Matches "1.1", "1 1" or "version 1" only — MPL 2.0 stays the default. */
    private val VERSION_1_X = Regex("(^| )1\\.\\d|(^| )1 \\d|version 1( |$)")

    /**
     * "Similar to Apache License but with the acknowledgment clause removed" is
     * the ASF's own description of its 2004 pre-release draft, not version 2.0.
     * It is kept as its own id so a reader is not told they have Apache-2.0.
     */
    private fun fromApache(name: String): String = if (name.contains("acknowledgment") || name.contains("acknowledgement")) LicenseIds.APACHE_BACKPORT else LicenseIds.APACHE_2_0

    private fun fromUrl(url: String): String =
        when {
            url.contains("apache.org/licenses/license-2.0") || url.contains("licenses/apache-2.0") -> LicenseIds.APACHE_2_0
            url.contains("licenses/mit") || url.contains("mit-license.php") -> LicenseIds.MIT
            url.contains("bsd-3-clause") -> LicenseIds.BSD_3_CLAUSE
            url.contains("bsd-2-clause") -> LicenseIds.BSD_2_CLAUSE
            url.contains("licenses/isc") -> LicenseIds.ISC
            url.contains("licenses/unlicense") -> LicenseIds.UNLICENSE
            url.contains("epl-1.0") || url.contains("eclipse-1.0") -> LicenseIds.EPL_1_0
            url.contains("epl-2.0") || url.contains("eclipse-2.0") -> LicenseIds.EPL_2_0
            url.contains("mpl-2.0") -> LicenseIds.MPL_2_0
            else -> LicenseIds.OTHER
        }

    /** Lower-cases, then folds punctuation so "Apache-2.0" and "Apache 2 0" match. */
    private fun String.collapse(): String {
        val text = this
        return buildString(text.length + 2) {
            append(' ')
            for (i in text.indices) {
                val c = text[i]
                val keepsDot =
                    c == '.' &&
                        i > 0 &&
                        text[i - 1].isDigit() &&
                        i + 1 < text.length &&
                        text[i + 1].isDigit()
                if (c.isLetterOrDigit() || keepsDot) append(c) else append(' ')
            }
            append(' ')
        }
    }
}
