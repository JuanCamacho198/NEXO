package com.nexo.presentation.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.feature.settings.R
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons

/** Asset written by `:feature:settings:generateLicensesAsset`. */
private const val LICENSES_ASSET = "licenses.txt"

/** Resource name of the canonical text for each licence id the build emits. */
private val LICENSE_TEXTS =
    mapOf(
        "Apache-2.0" to R.raw.license_apache_2_0,
        "Apache-2.0-WITH-ACKNOWLEDGMENT" to R.raw.license_apache_2_0,
        "MIT" to R.raw.license_mit,
        "BSD-2-Clause" to R.raw.license_bsd_2_clause,
        "BSD-3-Clause" to R.raw.license_bsd_3_clause,
        "ISC" to R.raw.license_isc,
    )

/**
 * Licences that arrive with no redistributable text, and why.
 *
 * Rendering an empty body would read as a bug; rendering somebody else's text
 * would be worse. Each of these says plainly that the full terms live with the
 * library itself, and the screen falls back to
 * [R.string.licenses_text_unavailable_body] for anything not listed here.
 */
private val LICENSE_TEXT_NOTES =
    mapOf(
        "Android-SDK" to R.string.licenses_text_android_sdk,
        "Apache-2.0-WITH-ACKNOWLEDGMENT" to R.string.licenses_text_apache_acknowledgment,
        "CDDL-1.0" to R.string.licenses_text_cddl,
        "EDL-1.0" to R.string.licenses_text_edl,
        "EPL-1.0" to R.string.licenses_text_epl,
        "EPL-2.0" to R.string.licenses_text_epl,
        "GPL-2.0" to R.string.licenses_text_gpl,
        "LGPL-2.1" to R.string.licenses_text_lgpl,
        "MPL-1.1" to R.string.licenses_text_mpl,
        "MPL-2.0" to R.string.licenses_text_mpl,
        "OTHER" to R.string.licenses_text_other,
        "NOT_DECLARED" to R.string.licenses_not_declared_note,
    )

/**
 * The order sections render in: licences carrying a copyright notice obligation
 * first, permissive before weak copyleft, then the two honesty buckets.
 *
 * A reader scanning for "what must I preserve" finds the permissive blocks at
 * the top instead of after 324 Apache entries, and a licence this build has
 * never seen sorts by identifier after the known ones rather than disappearing.
 */
private val LICENSE_ORDER =
    listOf(
        "Apache-2.0",
        "MIT",
        "BSD-3-Clause",
        "BSD-2-Clause",
        "ISC",
        "MPL-2.0",
        "EPL-2.0",
        "EDL-1.0",
        "CDDL-1.0",
        "LGPL-2.1",
        "GPL-2.0",
        "MPL-1.1",
        "EPL-1.0",
        "Apache-2.0-WITH-ACKNOWLEDGMENT",
        "Android-SDK",
        "CC0-1.0",
        "Unlicense",
        "OTHER",
        "NOT_DECLARED",
    )

/** One `group:name:version<TAB>LICENCE_ID` entry of the bundled inventory. */
internal data class ThirdPartyNotice(
    val group: String,
    val name: String,
    val version: String,
    val licenseId: String,
) {
    /** The part a reader recognises: `androidx.compose.material3:material3`. */
    val label: String get() = "$group:$name"
}

/** One licence section: the id, the libraries under it and its full text. */
internal data class LicenseGroup(
    val id: String,
    val notices: List<ThirdPartyNotice>,
    val textResource: Int?,
    val noteResource: Int?,
)

/** Splits an inventory line into coordinate and licence id. */
private fun parseNoticeLine(line: String): ThirdPartyNotice? {
    val separator = line.indexOf('\t')
    // No tab means the line predates the licence column. It is dropped, not
    // guessed at: an entry with no declared licence must say NOT_DECLARED, and
    // a legacy line cannot tell that apart from a corrupted one.
    if (separator <= 0) return null
    val coordinate = line.substring(0, separator).trim()
    val licenseId = line.substring(separator + 1).trim()
    if (licenseId.isEmpty()) return null
    val parts = coordinate.split(':')
    if (parts.size < 3) return null
    return ThirdPartyNotice(
        group = parts[0],
        name = parts[1],
        version = parts.drop(2).joinToString(":"),
        licenseId = licenseId,
    )
}

/**
 * Parses the bundled `licenses.txt`, skipping the `#` header comments.
 *
 * Pure and synchronous: the file is an APK asset, so there is nothing to await,
 * no network and no first-launch failure mode. A malformed line is dropped
 * rather than thrown, and duplicates are collapsed so a resolved BOM entry never
 * shows twice.
 */
internal fun parseThirdPartyNotices(raw: String): List<ThirdPartyNotice> =
    raw
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull(::parseNoticeLine)
        .distinctBy { it.label }
        .sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
        .toList()

/**
 * Buckets the inventory by licence, in [LICENSE_ORDER].
 *
 * Order is stable across builds for two reasons: known ids follow the declared
 * list, and an unknown id sorts by identifier with everything after it. That is
 * what keeps a section from jumping position when one dependency bumps.
 */
internal fun groupNoticesByLicense(notices: List<ThirdPartyNotice>): List<LicenseGroup> {
    val rank = LICENSE_ORDER.withIndex().associate { (index, id) -> id to index }
    return notices
        .groupBy { it.licenseId }
        .toList()
        .sortedWith(compareBy({ rank[it.first] ?: LICENSE_ORDER.size }, { it.first }))
        .map { (id, group) ->
            LicenseGroup(
                id = id,
                notices = group.sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() })),
                textResource = LICENSE_TEXTS[id],
                noteResource = LICENSE_TEXT_NOTES[id],
            )
        }
}

/** Reads a bundled licence text; empty when the raw resource is missing. */
private fun readLicenseText(
    context: android.content.Context,
    resource: Int,
): String =
    runCatching {
        context.resources
            .openRawResource(resource)
            .bufferedReader()
            .use { it.readText() }
            .trim()
    }.getOrDefault("")

/**
 * Licence attribution, reachable from About.
 *
 * NEXO's own licence is a discrete block at the top; the third-party notices the
 * APK ships follow, grouped by the licence each library declared in its POM and
 * each carrying the full text of that licence. Grouping by licence rather than
 * by artifact group is what turns the page from an inventory into attribution:
 * Apache-2.0 and MIT both require the notice and the terms to travel with a
 * binary, so the page has to show the terms, not just the names.
 *
 * A library whose POM declared no licence lands in its own section that says so
 * — it is never folded into a permissive licence it might not have.
 *
 * Everything read here is an APK asset or a raw resource, so the page works with
 * no network and no first-launch failure mode.
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notices =
        remember(context) {
            val raw =
                runCatching {
                    context.assets
                        .open(LICENSES_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull()
            raw?.let(::parseThirdPartyNotices).orEmpty()
        }
    val groups = remember(notices) { groupNoticesByLicense(notices) }

    NexoSettingsSubPage(
        title = stringResource(R.string.about_licenses),
        onBack = onBack,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProductLicenseBlock() }

            item {
                LicenseSectionHeader(
                    title = stringResource(R.string.licenses_third_party_title),
                    subtitle =
                        if (notices.isEmpty()) {
                            stringResource(R.string.licenses_unavailable_body)
                        } else {
                            stringResource(R.string.licenses_third_party_count, notices.size)
                        },
                )
            }

            groups.forEach { group ->
                item(key = "license-${group.id}") {
                    LicenseGroupHeader(
                        id = group.id,
                        count = group.notices.size,
                    )
                }
                items(group.notices, key = { "artifact-${it.licenseId}-${it.label}" }) { notice ->
                    LibraryRow(notice)
                }
                item(key = "license-text-${group.id}") {
                    LicenseTextBlock(group)
                }
            }
        }
    }
}

/** NEXO's own licence, kept discrete from the library notices. */
@Composable
private fun ProductLicenseBlock() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.licenses_product_title),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.licenses_product_body),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The licence identifier and how many libraries declare it. */
@Composable
private fun LicenseGroupHeader(
    id: String,
    count: Int,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = id,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.licenses_license_group_count, count),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One library: name, version, and the coordinate it resolves to. */
@Composable
private fun LibraryRow(notice: ThirdPartyNotice) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
    ) {
        Text(
            text = notice.name,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.licenses_library_coordinate, notice.group, notice.version),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The full text of one licence, collapsed by default.
 *
 * Collapsed because the Apache text alone is 11 KB: expanded by default it would
 * bury every other section. Expanded state is keyed per licence and survives a
 * configuration change, so opening one section never closes another.
 */
@Composable
private fun LicenseTextBlock(group: LicenseGroup) {
    val context = LocalContext.current
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val text =
        remember(group.id, group.textResource) {
            group.textResource?.let { readLicenseText(context, it) }.orEmpty()
        }
    val note = group.noteResource?.let { stringResource(it) }
    val label =
        if (expanded) {
            stringResource(R.string.licenses_text_hide)
        } else {
            stringResource(R.string.licenses_text_show)
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A licence with no bundled text still gets its explanation expanded:
        // hiding the "why" behind a tap would leave a reader with a bare list.
        if (text.isEmpty()) {
            note?.let { body ->
                NotDeclaredNote(body)
            }
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (expanded) NexoIcons.ChevronUp else NexoIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            note?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The "this library declares no licence" statement, marked so it is not scrolled past. */
@Composable
private fun NotDeclaredNote(body: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.licenses_not_declared_title),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
