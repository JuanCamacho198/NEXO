package com.nexo.presentation.feature.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.BUILTIN_OPENLIBRARY
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogSources
import com.nexo.domain.access.resolveAccess
import com.nexo.domain.usecase.DownloadImportState
import com.nexo.presentation.feature.library.formatFileSize
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.viewmodel.DiscoverDetailStatus
import com.nexo.ui.components.atoms.NexoBookCover
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoDivider
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.atoms.NexoSkeletonBox

/**
 * A derivable off-app link for a catalog book, with the label that names the
 * actual destination (the CTA must not claim Open Library when it points at
 * Project Gutenberg).
 */
internal data class DiscoverExternalLink(
    val url: String,
    @param:StringRes val labelRes: Int,
)

/**
 * Detail bottom sheet (base, existing fields only).
 *
 * Deliberately NOT [com.nexo.ui.components.atoms.NexoBottomSheet]: that
 * atom forces a centered `titleLarge` header plus uniform 24dp padding, which
 * fights the designed head-row layout. Uses M3 [ModalBottomSheet] directly with
 * `skipPartiallyExpanded = true` and composes its own content from the shared
 * atoms ([NexoBookCover], [NexoDivider], [NexoButton],
 * [DiscoverChipRow]).
 *
 * Every optional element is hidden when its field is absent — no placeholder
 * text, no empty chips row, no empty label. The download CTA is state-driven:
 * it downloads and imports in-app (progress, cancel, retry) and falls back to
 * opening `downloadUrl` externally only when no handler is supplied.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DiscoverDetailSheet(
    detail: CatalogBook?,
    detailStatus: DiscoverDetailStatus,
    download: DownloadImportState = DownloadImportState.Idle,
    onDownload: (() -> Unit)? = null,
    onCancelDownload: () -> Unit = {},
    onDismiss: () -> Unit,
    /**
     * U5 access state for the "Dónde leerlo" section. Null (the "Ver todo"
     * section screen, whose ViewModel owns no access state) derives the
     * generic U3 links locally — zero I/O, no consent needed.
     */
    accessState: AccessResolverState? = null,
    /** U5 addon items sheet state ([AddonReadState.Hidden] hides it). */
    addonRead: AddonReadState = AddonReadState.Hidden,
    /** Display name of the addon owning [addonRead]. */
    addonReadName: String = "",
    /** Display name of the addon that sourced [detail], if any. */
    addonName: String? = null,
    onOpenAddonRead: (addonId: String) -> Unit = {},
    onAllowAccessConsent: () -> Unit = {},
    onDenyAccessConsent: () -> Unit = {},
    onAllowAddonConsent: () -> Unit = {},
    onDenyAddonConsent: () -> Unit = {},
    onDismissAddonRead: () -> Unit = {},
    onRetryAddonRead: () -> Unit = {},
    onRetryAccess: () -> Unit = {},
) {
    if (detailStatus == DiscoverDetailStatus.CLOSED) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showReadAccess by remember(detailStatus) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color(0x66000000),
        containerColor = NexoColors.surface,
    ) {
        when {
            detailStatus == DiscoverDetailStatus.LOADING ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            detailStatus == DiscoverDetailStatus.NOT_FOUND || detail == null ->
                Text(
                    text =
                        stringResource(
                            if (detailStatus == DiscoverDetailStatus.NOT_FOUND) {
                                R.string.discover_detail_not_found
                            } else {
                                R.string.discover_error_upstream
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            else ->
                DiscoverDetailContent(
                    detail = detail,
                    download = download,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    accessState = accessState,
                    addonName = addonName,
                    onOpenReadAccess = { showReadAccess = true },
                    onOpenAddonRead = onOpenAddonRead,
                    onAllowAccessConsent = onAllowAccessConsent,
                    onDenyAccessConsent = onDenyAccessConsent,
                    onRetryAccess = onRetryAccess,
                )
        }
    }

    if (showReadAccess &&
        detail != null &&
        detailStatus != DiscoverDetailStatus.LOADING &&
        detailStatus != DiscoverDetailStatus.NOT_FOUND
    ) {
        ReadAccessSheet(
            state = accessState ?: remember(detail) { derivedAccess(detail) },
            download = download,
            onDownloadInApp = { onDownload?.invoke() },
            onCancelDownload = onCancelDownload,
            onRetry = onRetryAccess,
            onAllowConsent = onAllowAccessConsent,
            onDenyConsent = onDenyAccessConsent,
            onDismiss = { showReadAccess = false },
        )
    }

    if (addonRead != AddonReadState.Hidden) {
        AddonReadSheet(
            addonName = addonReadName,
            state = addonRead,
            onAllowConsent = onAllowAddonConsent,
            onDenyConsent = onDenyAddonConsent,
            onRetry = onRetryAddonRead,
            onDismiss = onDismissAddonRead,
        )
    }
}

/**
 * Locally derived access for hosts without an access state (the "Ver todo"
 * section screen): generic U3 links, zero I/O, no consent involved.
 */
private fun derivedAccess(detail: CatalogBook): AccessResolverState =
    mapAccessState(
        isOnline = true,
        consentRequiredAddonId = null,
        hasConsent = true,
        access = resolveAccess(detail),
        failed = false,
    )

@Composable
private fun DiscoverDetailContent(
    detail: CatalogBook,
    download: DownloadImportState,
    onDownload: (() -> Unit)?,
    onCancelDownload: () -> Unit,
    accessState: AccessResolverState?,
    addonName: String?,
    onOpenReadAccess: () -> Unit,
    onOpenAddonRead: (addonId: String) -> Unit,
    onAllowAccessConsent: () -> Unit,
    onDenyAccessConsent: () -> Unit,
    onRetryAccess: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val externalLink = discoverExternalLink(detail)
    val effectiveAccess = accessState ?: remember(detail) { derivedAccess(detail) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DiscoverDetailHead(detail = detail)

        if (detail.subjects.isNotEmpty()) {
            DiscoverDetailSubjectSection(subjects = detail.subjects)
        }

        DiscoverDetailParagraph(
            labelRes = R.string.discover_detail_description_label,
            body = discoverDescription(detail),
        )

        val formatLabels = discoverFormatLabels(detail.formats)
        if (formatLabels.isNotEmpty()) {
            DiscoverDetailChipRow(labels = formatLabels.map { stringResource(it) })
        }

        NexoDivider()

        DiscoverAccessSection(
            detail = detail,
            download = download,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            accessState = effectiveAccess,
            addonName = addonName,
            onOpenReadAccess = onOpenReadAccess,
            onOpenAddonRead = onOpenAddonRead,
            onAllowAccessConsent = onAllowAccessConsent,
            onDenyAccessConsent = onDenyAccessConsent,
            onRetryAccess = onRetryAccess,
        )

        externalLink?.let { link ->
            NexoButton(
                onClick = { uriHandler.openUri(link.url) },
                variant = NexoButtonVariant.OUTLINED,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(link.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * U5 access section ("Dónde leerlo / disponible en otro lado"): replaces
 * the old single download CTA.
 *
 * The in-app download CTA renders only when the resolved access gates it
 * (`isPublicDomain == true` + `https` download URL) — the existing PD flow
 * is unchanged; every other book offers the grouped web options through
 * the full access sheet. Addon-sourced books additionally offer their
 * provider's resolved items entry.
 */
@Composable
private fun DiscoverAccessSection(
    detail: CatalogBook,
    download: DownloadImportState,
    onDownload: (() -> Unit)?,
    onCancelDownload: () -> Unit,
    accessState: AccessResolverState,
    addonName: String?,
    onOpenReadAccess: () -> Unit,
    onOpenAddonRead: (addonId: String) -> Unit,
    onAllowAccessConsent: () -> Unit,
    onDenyAccessConsent: () -> Unit,
    onRetryAccess: () -> Unit,
) {
    val addonId = remember(detail) { CatalogSources.addonIdOf(detail.provider) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.read_access_title),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
        )
        Text(
            text = stringResource(R.string.read_access_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = NexoColors.textSecondary,
        )
        when (accessState) {
            AccessResolverState.Loading -> {
                NexoSkeletonBox(modifier = Modifier.fillMaxWidth())
                NexoSkeletonBox(modifier = Modifier.fillMaxWidth())
            }
            is AccessResolverState.Loaded -> {
                if (accessState.access.canDownloadInApp) {
                    if (onDownload != null) {
                        DiscoverDownloadCta(
                            download = download,
                            onDownload = onDownload,
                            onCancelDownload = onCancelDownload,
                        )
                    } else {
                        // Host without the download bridge (the "Ver todo"
                        // section screen): preserve the external-open CTA for
                        // the gated PD download rather than a dead one.
                        val gatedUrl = accessState.access.downloadUrl
                        if (gatedUrl != null) {
                            val uriHandler = LocalUriHandler.current
                            NexoButton(
                                onClick = { uriHandler.openUri(gatedUrl) },
                                variant = NexoButtonVariant.FILLED,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.discover_download),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                if (addonId != null) {
                    NexoButton(
                        onClick = { onOpenAddonRead(addonId) },
                        variant = NexoButtonVariant.OUTLINED,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    R.string.discover_rail_from,
                                    addonName ?: addonId,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                NexoButton(
                    onClick = onOpenReadAccess,
                    variant = NexoButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.read_access_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            AccessResolverState.Empty ->
                Text(
                    text = stringResource(R.string.read_access_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexoColors.textSecondary,
                )
            AccessResolverState.Error -> {
                Text(
                    text = stringResource(R.string.discover_error_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexoColors.textSecondary,
                )
                NexoButton(
                    text = stringResource(R.string.discover_retry),
                    onClick = onRetryAccess,
                    variant = NexoButtonVariant.OUTLINED,
                )
            }
            AccessResolverState.Offline -> {
                Text(
                    text = stringResource(R.string.discover_offline_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexoColors.textSecondary,
                )
                NexoButton(
                    text = stringResource(R.string.discover_retry),
                    onClick = onRetryAccess,
                    variant = NexoButtonVariant.OUTLINED,
                )
            }
            is AccessResolverState.ConsentRequired -> {
                Text(
                    text =
                        stringResource(
                            R.string.addon_consent_body,
                            addonName ?: accessState.addonId,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexoColors.textSecondary,
                )
                NexoButton(
                    text = stringResource(R.string.addon_consent_allow),
                    onClick = onAllowAccessConsent,
                    modifier = Modifier.fillMaxWidth(),
                )
                NexoButton(
                    text = stringResource(R.string.addon_consent_deny),
                    onClick = onDenyAccessConsent,
                    variant = NexoButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * State-driven download CTA: `Idle` enables the download, in-flight states show
 * progress plus an explicit cancel, `Success` is a disabled confirmation,
 * `Duplicate` is a neutral message and `Failure` is an inline error with retry.
 */
@Composable
private fun DiscoverDownloadCta(
    download: DownloadImportState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (download) {
            is DownloadImportState.Idle -> {
                NexoButton(
                    onClick = onDownload,
                    variant = NexoButtonVariant.FILLED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.discover_download),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is DownloadImportState.Downloading -> {
                val total = download.totalBytes
                if (total != null && total > 0L) {
                    NexoProgressBar(
                        progress = download.bytesSoFar.toFloat() / total.toFloat(),
                        showPercentage = true,
                    )
                    Text(
                        text = "${formatFileSize(download.bytesSoFar)} / ${formatFileSize(total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NexoColors.textSecondary,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = formatFileSize(download.bytesSoFar),
                        style = MaterialTheme.typography.labelSmall,
                        color = NexoColors.textSecondary,
                    )
                }
                NexoButton(
                    onClick = onCancelDownload,
                    variant = NexoButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.discover_download_cancel),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is DownloadImportState.Importing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                NexoButton(
                    onClick = onCancelDownload,
                    variant = NexoButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.discover_download_cancel),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is DownloadImportState.Success -> {
                NexoButton(
                    onClick = { },
                    enabled = false,
                    variant = NexoButtonVariant.FILLED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.discover_imported),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is DownloadImportState.Duplicate -> {
                Text(
                    text = stringResource(R.string.discover_duplicate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textSecondary,
                )
            }
            is DownloadImportState.Failure -> {
                Text(
                    text = stringResource(R.string.discover_download_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.errorSoft,
                )
                NexoButton(
                    onClick = onDownload,
                    variant = NexoButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.discover_retry),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Head row: 72x108 cover, title, author line and the language chips. */
@Composable
private fun DiscoverDetailHead(detail: CatalogBook) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (detail.coverUrl.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier
                        .size(width = 72.dp, height = 108.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NexoColors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bookInitial(detail.title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                    fontWeight = FontWeight.Bold,
                    color = NexoColors.primary,
                )
            }
        } else {
            NexoBookCover(
                coverUrl = detail.coverUrl,
                title = detail.title,
                modifier = Modifier.size(width = 72.dp, height = 108.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                fontWeight = FontWeight.SemiBold,
                color = NexoColors.textPrimary,
            )
            if (detail.authors.isNotEmpty()) {
                Text(
                    text =
                        stringResource(
                            R.string.discover_by_authors,
                            detail.authors.joinToString(", "),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textSecondary,
                )
            }
            if (detail.languages.isNotEmpty()) {
                Spacer(modifier = Modifier.size(2.dp))
                DiscoverDetailChipRow(
                    labels = detail.languages,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                )
            }
        }
    }
}

/** "TEMAS" label over the subject chips, both on the `bg_card_hover` token. */
@Composable
private fun DiscoverDetailSubjectSection(subjects: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DiscoverDetailLabel(text = stringResource(R.string.discover_detail_subjects_label))
        DiscoverDetailChipRow(labels = subjects)
    }
}

/**
 * Labeled paragraph block. Hides entirely when [body] is null — the hiding rule
 * for a field the model cannot provide yet.
 */
@Composable
private fun DiscoverDetailParagraph(
    @StringRes labelRes: Int,
    body: String?,
) {
    if (body.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DiscoverDetailLabel(text = stringResource(labelRes))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = NexoColors.textSecondary,
        )
    }
}

@Composable
private fun DiscoverDetailLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = NexoColors.textSecondary,
    )
}

/** Non-interactive chip row rendered on `bg_card_hover`. */
@Composable
private fun DiscoverDetailChipRow(
    labels: List<String>,
    contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp),
) {
    DiscoverChipRow(
        chips = labels.map { DiscoverChip(label = it) },
        onChipClick = { },
        contentPadding = contentPadding,
        chipBackground = NexoColors.surfaceVariant,
        chipLabelColor = NexoColors.textSecondary,
    )
}

/**
 * Off-app URL derivable from fields that already exist on the model. Returns null
 * for anything that is not a resolvable first-party id, which hides the CTA.
 */
internal fun discoverExternalLink(book: CatalogBook): DiscoverExternalLink? {
    if (book.provider == BUILTIN_GUTENDEX) {
        val numericId = book.id.removePrefix("gutendex:").toIntOrNull()
        if (numericId != null && numericId > 0) {
            return DiscoverExternalLink(
                url = "https://www.gutenberg.org/ebooks/$numericId",
                labelRes = R.string.discover_detail_external_gutenberg,
            )
        }
        return null
    }
    if (book.provider == BUILTIN_OPENLIBRARY) {
        val key = book.id.removePrefix("openlibrary:")
        if (key.startsWith("/")) {
            return DiscoverExternalLink(
                url = "https://openlibrary.org$key",
                labelRes = R.string.discover_detail_external_openlibrary,
            )
        }
        return null
    }
    return null
}

/**
 * Format labels derived from the real `formats` map keys, in a stable display
 * order. Unknown keys are skipped; an empty map yields an empty list so the
 * format row hides.
 */
internal fun discoverFormatLabels(formats: Map<String, String>): List<Int> {
    val byMime =
        listOf(
            "application/epub+zip" to R.string.discover_format_epub,
            "application/x-mobipocket-ebook" to R.string.discover_format_mobi,
            "text/plain; charset=utf-8" to R.string.discover_format_txt,
            "text/html" to R.string.discover_format_html,
        )
    return byMime.filter { formats.containsKey(it.first) }.map { it.second }
}

/**
 * Description text for the DESCRIPCIÓN block. Null/blank hides the block rather
 * than showing a placeholder paragraph.
 */
internal fun discoverDescription(book: CatalogBook): String? = book.description?.takeIf { it.isNotBlank() }
