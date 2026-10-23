package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonClient
import com.nexo.data.remote.addons.protocol.AddonDescriptorV3
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import com.nexo.data.remote.addons.protocol.AddonProtocolConstants
import com.nexo.data.remote.addons.protocol.AddonResourceV3
import com.nexo.data.remote.addons.protocol.IdRequest
import com.nexo.data.remote.addons.protocol.MetaPreview
import com.nexo.data.remote.addons.protocol.TtlCache
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import kotlinx.coroutines.CancellationException

private const val META_RESOURCE = "meta"
private const val HTTPS_PREFIX = "https://"

/** The library cache is deliberately longer than the catalog one: 24 h. */
private const val LIBRARY_META_CACHE_TTL_MS = 24L * 60 * 60 * 1000

/** A cover is an image, not a payload: the 512 KiB body cap does not apply. */
private const val MAX_COVER_BYTES = 2 * 1024 * 1024

private const val TAG_SEPARATOR = ", "

/** Metadata an addon can contribute to a book the reader already owns. */
data class LibraryMeta(
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val language: String? = null,
    val genre: String? = null,
    val tags: String? = null,
    val coverUrl: String? = null,
)

/**
 * Enrichment seam. [LibraryMetadataService] is the production implementation;
 * the library repository only needs the merge result, never the transport.
 */
interface LibraryMetaSource {
    /**
     * The book with whatever the consulted addons could contribute, or `null`
     * when nothing could be filled. A [force] ask bypasses the response cache.
     */
    suspend fun enrich(
        book: Book,
        force: Boolean = false,
    ): Book?
}

/**
 * The library-side metadata contract: which ids to ask for, which addons may be
 * asked, and what a book may take from an answer. Pure, no I/O.
 */
object LibraryMetadataPolicy {
    private val DIGITS = Regex("^\\d+$")

    private const val ISBN_10 = 10
    private const val ISBN_13 = 13
    private const val ISBN_PREFIX = "isbn:"

    /**
     * The one id an addon can be asked about, in precedence order: the frozen
     * namespaced provenance id, else a bare 10/13-digit ISBN under `isbn:`.
     *
     * An EPUB with no ISBN and no namespaced source id yields `[]` and is
     * skipped. That is a documented coverage limitation: title matching would
     * guess, and a guess here writes into the reader's own library.
     */
    fun candidateIds(
        sourceItemId: String?,
        isbn13: String?,
    ): List<String> {
        val itemId = sourceItemId?.trim().orEmpty()
        if (itemId.isNotEmpty() && AddonProtocolConstants.ID_PREFIXES.any { itemId.startsWith(it) }) {
            return listOf(itemId)
        }
        val isbn = isbn13?.trim().orEmpty()
        val isbnLength = isbn.length == ISBN_10 || isbn.length == ISBN_13
        if (isbnLength && DIGITS.matches(isbn)) return listOf(ISBN_PREFIX + isbn)
        return emptyList()
    }

    /**
     * `enabled` AND declares `meta` AND the declaration's prefixes (per
     * resource, else top level) match a candidate id.
     *
     * An addon that declares `meta` with NO id prefixes is never consulted: with
     * nothing to match on, every owned book would be sent to it, and this is the
     * one resource that discloses what the reader owns.
     */
    fun shouldConsultLibraryMeta(
        addon: AddonDescriptorV3,
        candidates: List<String>,
    ): Boolean {
        if (!addon.enabled || candidates.isEmpty()) return false
        val declaration = metaDeclaration(addon.manifest) ?: return false
        val prefixes = effectivePrefixes(declaration.idPrefixes, addon.manifest.idPrefixes)
        if (prefixes.isEmpty()) return false
        return candidates.any { candidate -> prefixes.any { candidate.startsWith(it) } }
    }

    /**
     * Fill only where the current value is null or blank, so an addon can never
     * overwrite what the reader or the EPUB itself already provided. The cover
     * arrives already persisted through [CoverStorage] by the caller.
     *
     * Identity, provenance, size and the whole reading state are untouched by
     * construction: this copies the book and rewrites six metadata fields.
     */
    fun mergeMeta(
        book: Book,
        meta: LibraryMeta,
        coverPath: String? = null,
    ): Pair<Book, Boolean> {
        val merged =
            book.copy(
                description = book.description.orFill(meta.description),
                publisher = book.publisher.orFill(meta.publisher),
                publishedDate = book.publishedDate.orFill(meta.publishedDate),
                language = book.language.orFill(meta.language),
                genre = book.genre.orFill(meta.genre),
                tags = book.tags.orFill(meta.tags),
                coverPath = if (book.coverPath.isNullOrBlank()) coverPath?.takeIf { it.isNotBlank() } else book.coverPath,
            )
        return merged to (merged != book)
    }

    /** String entries and `{ "name": "meta" }` entries are the same declaration. */
    private fun metaDeclaration(manifest: AddonManifestV3): AddonResourceV3? = manifest.resources.firstOrNull { it.name == META_RESOURCE }

    private fun effectivePrefixes(
        perResource: List<String>?,
        topLevel: List<String>?,
    ): List<String> =
        when {
            !perResource.isNullOrEmpty() -> perResource
            !topLevel.isNullOrEmpty() -> topLevel
            else -> emptyList()
        }

    private fun String?.orFill(candidate: String?): String? = if (isNullOrBlank()) candidate?.takeIf { it.isNotBlank() } else this
}

/**
 * The ask, for books already on the shelf.
 *
 * Only books the reader owns are ever asked about, only addons that (a) are
 * enabled, (b) declare `meta` with id prefixes matching the book's identifier,
 * and (c) hold the `library_meta` consent grant are consulted — in install
 * order, one response cached per `(addonId, candidateId)` for 24 h.
 *
 * A failing addon is silent: a timeout, a non-2xx, a malformed payload or an
 * unknown id contributes nothing and never surfaces an error. Nothing here runs
 * on a list scroll; the callers are the import path and an explicit refresh.
 */
class LibraryMetadataService(
    private val installedAddons: suspend () -> List<InstalledAddonRow>,
    private val client: AddonClient,
    private val consent: AddonConsentStore = InMemoryAddonConsentStore(),
    private val cache: TtlCache = TtlCache(),
    private val coverStorage: CoverStorage? = null,
    private val fetchCover: suspend (String) -> ByteArray? = { null },
) : LibraryMetaSource {
    override suspend fun enrich(
        book: Book,
        force: Boolean,
    ): Book? {
        val candidates = LibraryMetadataPolicy.candidateIds(book.sourceItemId, book.isbn13)
        if (candidates.isEmpty()) return null

        val rows = installed()
        var current = book
        var changed = false
        for (row in rows) {
            val addon = row.toDescriptor()
            if (!LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, candidates)) continue
            if (!consent.hasLibraryMetaConsent(addon.id)) continue
            for (candidate in candidates) {
                val meta = metaFor(addon, candidate, force) ?: continue
                val coverPath = resolveCover(current, meta)
                val (merged, didChange) = LibraryMetadataPolicy.mergeMeta(current, meta, coverPath)
                current = merged
                changed = changed || didChange
            }
        }
        return current.takeIf { changed }
    }

    /** A registry read failure owns no metadata: the ask is skipped, silently. */
    private suspend fun installed(): List<InstalledAddonRow> =
        try {
            installedAddons()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun metaFor(
        addon: AddonDescriptorV3,
        candidateId: String,
        force: Boolean,
    ): LibraryMeta? {
        val key = cacheKeyOf(addon.id, candidateId)
        if (!force) (cache.get(key) as? LibraryMeta)?.let { return it }

        val item =
            try {
                client.getMeta(listOf(addon), IdRequest(id = candidateId)).items.firstOrNull()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } ?: return null

        val meta = item.meta.toLibraryMeta()
        cache.set(key, meta, LIBRARY_META_CACHE_TTL_MS)
        return meta
    }

    /**
     * A cover is fetched only for a book that has none, over https only, and is
     * persisted through the same [CoverStorage] import writes through. A failed
     * cover is a missing cover, never an import failure.
     */
    private suspend fun resolveCover(
        book: Book,
        meta: LibraryMeta,
    ): String? {
        if (!book.coverPath.isNullOrBlank()) return null
        val url = meta.coverUrl?.takeIf { it.startsWith(HTTPS_PREFIX) } ?: return null
        val storage = coverStorage ?: return null

        val bytes =
            try {
                fetchCover(url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        return storage.saveCover(bookId = book.id, coverBytes = bytes).getOrNull()
    }

    private fun cacheKeyOf(
        addonId: String,
        candidateId: String,
    ): String = "library-meta|$addonId|$candidateId"
}

/**
 * The protocol's `meta` preview mapped onto the library's metadata columns.
 * Every field the preview can carry is mapped: `releaseInfo` is the protocol's
 * name for the publication date, and the first language/subject become the
 * single-valued `language`/`genre` columns.
 */
internal fun MetaPreview.toLibraryMeta(): LibraryMeta =
    LibraryMeta(
        description = description,
        publisher = publisher,
        publishedDate = releaseInfo,
        language = languages.firstOrNull(),
        genre = subjects.firstOrNull(),
        tags = subjects.takeIf { it.isNotEmpty() }?.joinToString(TAG_SEPARATOR),
        coverUrl = poster,
    )

internal fun InstalledAddonRow.toDescriptor(): AddonDescriptorV3 = AddonDescriptorV3(id, url, addedAt, enabled, manifest)
