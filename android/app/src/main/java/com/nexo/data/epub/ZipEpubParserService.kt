package com.nexo.data.epub

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class ZipEpubParserService : EpubParserService {
    override suspend fun extractMetadata(inputStream: InputStream): Result<EpubMetadata> =
        runCatching {
            val zipBytes = inputStream.use { it.readBytes() }
            val rootFilePath =
                readRootFilePath(zipBytes)
                    ?: throw IllegalArgumentException("Invalid EPUB: missing rootfile in container.xml")

            val packageDocument =
                readZipEntryBytes(zipBytes, rootFilePath)
                    ?: throw IllegalArgumentException("Invalid EPUB: missing OPF package document")

            val parsed = parsePackageDocument(packageDocument)
            val title =
                parsed.title?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Invalid EPUB: metadata title missing")

            val coverBytes =
                parsed.coverHref
                    ?.let { href ->
                        resolvePath(rootFilePath, href)
                    }?.let { coverPath -> readZipEntryBytes(zipBytes, coverPath) }

            // ── Page estimation ──────────────────────────────────────
            val estimatedPageCount =
                estimatePages(
                    zipBytes = zipBytes,
                    rootFilePath = rootFilePath,
                    spineItemRefs = parsed.spineItemRefs,
                    manifestById = parsed.manifestById,
                )

            EpubMetadata(
                title = title,
                author = parsed.author?.takeIf { it.isNotBlank() },
                description = parsed.description?.takeIf { it.isNotBlank() },
                chapterCount = parsed.spineItemCount,
                estimatedPageCount = estimatedPageCount,
                coverImageBytes = coverBytes,
                language = parsed.language?.takeIf { it.isNotBlank() },
                publisher = parsed.publisher?.takeIf { it.isNotBlank() },
                tags = parsed.subjects,
                publishedDate = parsed.publishedDate?.takeIf { it.isNotBlank() },
            )
        }

    private fun readRootFilePath(zipBytes: ByteArray): String? {
        val containerXml = readZipEntryBytes(zipBytes, CONTAINER_XML_PATH) ?: return null
        val document = parseXml(containerXml)
        val rootFiles = document.getElementsByTagName("*")
        for (index in 0 until rootFiles.length) {
            val node = rootFiles.item(index)
            if (node is Element && node.localTagName() == "rootfile") {
                return node.getAttribute("full-path").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun parsePackageDocument(opfBytes: ByteArray): ParsedPackageDocument {
        val document = parseXml(opfBytes)
        val title = firstElementTextByLocalName(document, "title")
        val author = firstElementTextByLocalName(document, "creator")
        val description = firstElementTextByLocalName(document, "description")
        val language = firstElementTextByLocalName(document, "language")
        val publisher = firstElementTextByLocalName(document, "publisher")
        val publishedDate = firstElementTextByLocalName(document, "date")
        // All dc:subject matches become tags; commas inside a subject are
        // stripped so the comma-separated encoding cannot be corrupted.
        val subjects =
            allElementsTextByLocalName(document, "subject")
                .map { it.replace(",", "").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val manifestById = mutableMapOf<String, String>()
        val allElements = document.getElementsByTagName("*")
        var coverItemId: String? = null
        val spineItemRefs = mutableListOf<String>()

        for (index in 0 until allElements.length) {
            val node = allElements.item(index)
            if (node !is Element) continue

            when (node.localTagName()) {
                "meta" -> {
                    if (node.getAttribute("name") == "cover") {
                        coverItemId = node.getAttribute("content").takeIf { it.isNotBlank() }
                    }
                }
                "item" -> {
                    val id = node.getAttribute("id").takeIf { it.isNotBlank() }
                    val href = node.getAttribute("href").takeIf { it.isNotBlank() }
                    if (id != null && href != null) {
                        manifestById[id] = href
                    }
                }
                "itemref" -> {
                    val idref = node.getAttribute("idref").takeIf { it.isNotBlank() }
                    if (idref != null) spineItemRefs.add(idref)
                }
            }
        }

        return ParsedPackageDocument(
            title = title,
            author = author,
            description = description,
            language = language,
            publisher = publisher,
            subjects = subjects,
            publishedDate = publishedDate,
            coverHref = coverItemId?.let(manifestById::get),
            spineItemCount = spineItemRefs.size,
            spineItemRefs = spineItemRefs,
            manifestById = manifestById,
        )
    }

    private fun readZipEntryBytes(
        zipBytes: ByteArray,
        entryPath: String,
    ): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryPath) {
                    return zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun resolvePath(
        basePath: String,
        relativePath: String,
    ): String {
        if (!relativePath.contains("../")) {
            val baseDir = basePath.substringBeforeLast('/', "")
            return if (baseDir.isBlank()) relativePath else "$baseDir/$relativePath"
        }

        val baseParts =
            basePath
                .substringBeforeLast('/', "")
                .split('/')
                .filter { it.isNotBlank() }
                .toMutableList()
        val relativeParts = relativePath.split('/').filter { it.isNotBlank() }

        relativeParts.forEach { part ->
            when (part) {
                "." -> Unit
                ".." -> if (baseParts.isNotEmpty()) baseParts.removeAt(baseParts.lastIndex)
                else -> baseParts.add(part)
            }
        }

        return baseParts.joinToString("/")
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // setFeature may throw on Android depending on the XML parser implementation
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun firstElementTextByLocalName(
        document: Document,
        localName: String,
    ): String? {
        val allElements = document.getElementsByTagName("*")
        for (index in 0 until allElements.length) {
            val node = allElements.item(index)
            if (node is Element && node.localTagName() == localName) {
                val value = node.textContent?.trim()
                if (!value.isNullOrBlank()) {
                    return value
                }
            }
        }
        return null
    }

    private fun allElementsTextByLocalName(
        document: Document,
        localName: String,
    ): List<String> {
        val allElements = document.getElementsByTagName("*")
        val values = mutableListOf<String>()
        for (index in 0 until allElements.length) {
            val node = allElements.item(index)
            if (node is Element && node.localTagName() == localName) {
                val value = node.textContent?.trim()
                if (!value.isNullOrBlank()) {
                    values.add(value)
                }
            }
        }
        return values
    }

    private fun Node.localTagName(): String = localName ?: nodeName.substringAfter(':', nodeName)

    private data class ParsedPackageDocument(
        val title: String?,
        val author: String?,
        val description: String? = null,
        val language: String? = null,
        val publisher: String? = null,
        val subjects: List<String> = emptyList(),
        val publishedDate: String? = null,
        val coverHref: String?,
        val spineItemCount: Int = 0,
        val spineItemRefs: List<String> = emptyList(),
        val manifestById: Map<String, String> = emptyMap(),
    )

    /**
     * Estimates page count for an EPUB by summing raw byte size of each
     * spine-referenced manifest entry, then applying:
     * ~5 chars/word, ~250 words/page.
     */
    private fun estimatePages(
        zipBytes: ByteArray,
        rootFilePath: String,
        spineItemRefs: List<String>,
        manifestById: Map<String, String>,
    ): Int? {
        if (spineItemRefs.isEmpty()) return null
        var totalChars = 0
        for (idref in spineItemRefs) {
            val href = manifestById[idref] ?: continue
            val resolvedPath = resolvePath(rootFilePath, href)
            val entryBytes = readZipEntryBytes(zipBytes, resolvedPath) ?: continue
            totalChars += entryBytes.size
        }
        if (totalChars == 0) return null
        val estimatedWords = totalChars / AVG_BYTES_PER_WORD
        val estimatedPages = estimatedWords / WORDS_PER_PAGE
        return estimatedPages.coerceAtLeast(1)
    }

    private companion object {
        const val CONTAINER_XML_PATH = "META-INF/container.xml"
        const val AVG_BYTES_PER_WORD = 5
        const val WORDS_PER_PAGE = 250
    }
}
