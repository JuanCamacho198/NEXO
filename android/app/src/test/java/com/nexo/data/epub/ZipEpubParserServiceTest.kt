package com.nexo.data.epub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEpubParserServiceTest {
    private val parser = ZipEpubParserService()

    @Test
    fun extractMetadata_returnsTitleAuthorAndCoverWhenPresent() =
        runBlocking {
            val coverBytes = byteArrayOf(1, 2, 3, 4)
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to
                                """
                                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                                    <rootfiles>
                                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                                    </rootfiles>
                                </container>
                                """.trimIndent().toByteArray(),
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Sample Book</dc:title>
                                        <dc:creator>Jane Doe</dc:creator>
                                        <meta name="cover" content="cover-image" />
                                    </metadata>
                                    <manifest>
                                        <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
                                    </manifest>
                                </package>
                                """.trimIndent().toByteArray(),
                            "OEBPS/images/cover.jpg" to coverBytes,
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            val metadata = result.getOrThrow()
            assertEquals("Sample Book", metadata.title)
            assertEquals("Jane Doe", metadata.author)
            assertNotNull(metadata.coverImageBytes)
            assertArrayEquals(coverBytes, metadata.coverImageBytes)
        }

    @Test
    fun extractMetadata_returnsFirstImageAsCoverWhenNoMarkerAndNoGuide() =
        runBlocking {
            val imageBytes = byteArrayOf(9, 8, 7, 6, 5)
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
                            // Marker-less, guide-less shape: EPUB3, ONE image whose
                            // name carries no cover word, referenced only from a
                            // chapter. This mirrors `Como Hablar con Cualquiera`.
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Markerless Book</dc:title>
                                        <dc:creator>Jane Doe</dc:creator>
                                        <dc:language>und</dc:language>
                                        <meta property="dcterms:modified">2021-05-04T00:00:00Z</meta>
                                    </metadata>
                                    <manifest>
                                        <item id="img0" href="img/img0.jpg" media-type="image/jpeg"/>
                                        <item id="page1" href="page1.xhtml" media-type="application/xhtml+xml"/>
                                    </manifest>
                                    <spine>
                                        <itemref idref="page1"/>
                                    </spine>
                                </package>
                                """.trimIndent().toByteArray(),
                            "OEBPS/img/img0.jpg" to imageBytes,
                            "OEBPS/page1.xhtml" to
                                """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                                    <img src="img/img0.jpg"/>
                                </body></html>
                                """.trimIndent().toByteArray(),
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            val metadata = result.getOrThrow()
            assertNotNull(metadata.coverImageBytes)
            assertArrayEquals(imageBytes, metadata.coverImageBytes)
        }

    @Test
    fun extractMetadata_returnsNullCoverWhenBookHasNoImageAtAll() =
        runBlocking {
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Imageless Book</dc:title>
                                    </metadata>
                                    <manifest>
                                        <item id="page1" href="page1.xhtml" media-type="application/xhtml+xml"/>
                                    </manifest>
                                    <spine>
                                        <itemref idref="page1"/>
                                    </spine>
                                </package>
                                """.trimIndent().toByteArray(),
                            "OEBPS/page1.xhtml" to
                                """
                                <html xmlns="http://www.w3.org/1999/xhtml"><body><p>text</p></body></html>
                                """.trimIndent().toByteArray(),
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            assertEquals(null, result.getOrThrow().coverImageBytes)
        }

    @Test
    fun extractMetadata_prefersNamedCoverOverFirstImage() =
        runBlocking {
            val coverBytes = byteArrayOf(1, 1, 1)
            val otherBytes = byteArrayOf(2, 2, 2)
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Heuristic Book</dc:title>
                                    </metadata>
                                    <manifest>
                                        <item id="a" href="img/first.jpg" media-type="image/jpeg"/>
                                        <item id="b" href="img/cover.jpg" media-type="image/jpeg"/>
                                    </manifest>
                                </package>
                                """.trimIndent().toByteArray(),
                            "OEBPS/img/first.jpg" to otherBytes,
                            "OEBPS/img/cover.jpg" to coverBytes,
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            assertArrayEquals(coverBytes, result.getOrThrow().coverImageBytes)
        }

    @Test
    fun extractMetadata_returnsGuideCoverHrefWhenDeclared() =
        runBlocking {
            val coverBytes = byteArrayOf(4, 4, 4, 4)
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Guide Book</dc:title>
                                    </metadata>
                                    <manifest>
                                        <item id="a" href="img/first.jpg" media-type="image/jpeg"/>
                                    </manifest>
                                    <guide>
                                        <reference type="cover" href="img/cover.jpg" title="Cover"/>
                                    </guide>
                                </package>
                                """.trimIndent().toByteArray(),
                            "OEBPS/img/first.jpg" to byteArrayOf(3, 3),
                            "OEBPS/img/cover.jpg" to coverBytes,
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            assertArrayEquals(coverBytes, result.getOrThrow().coverImageBytes)
        }

    @Test
    fun extractMetadata_returnsFailureForInvalidEpub() =
        runBlocking {
            val invalidEpubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "book.txt" to "not an epub".toByteArray(),
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(invalidEpubBytes))

            assertTrue(result.isFailure)
        }

    @Test
    fun extractMetadata_returnsLanguagePublisherSubjectsAndDate() =
        runBlocking {
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Sample Book</dc:title>
                                        <dc:creator>Jane Doe</dc:creator>
                                        <dc:language>en</dc:language>
                                        <dc:publisher>Acme Press</dc:publisher>
                                        <dc:subject>Fiction, Modern</dc:subject>
                                        <dc:subject>Drama</dc:subject>
                                        <dc:subject>  Fantasy  </dc:subject>
                                        <dc:subject>Drama</dc:subject>
                                        <dc:date>2021-05-04</dc:date>
                                    </metadata>
                                    <manifest/>
                                </package>
                                """.trimIndent().toByteArray(),
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            val metadata = result.getOrThrow()
            assertEquals("en", metadata.language)
            assertEquals("Acme Press", metadata.publisher)
            // Commas stripped inside subjects, trimmed, blanks dropped, deduped
            assertEquals(listOf("Fiction Modern", "Drama", "Fantasy"), metadata.tags)
            assertEquals("2021-05-04", metadata.publishedDate)
        }

    @Test
    fun extractMetadata_returnsNullForMissingMetadataFields() =
        runBlocking {
            val epubBytes =
                buildEpub(
                    entries =
                        mapOf(
                            "META-INF/container.xml" to CONTAINER_XML.toByteArray(),
                            "OEBPS/content.opf" to
                                """
                                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                                        <dc:title>Bare Book</dc:title>
                                    </metadata>
                                    <manifest/>
                                </package>
                                """.trimIndent().toByteArray(),
                        ),
                )

            val result = parser.extractMetadata(ByteArrayInputStream(epubBytes))

            assertTrue(result.isSuccess)
            val metadata = result.getOrThrow()
            assertEquals(null, metadata.language)
            assertEquals(null, metadata.publisher)
            assertEquals(emptyList<String>(), metadata.tags)
            assertEquals(null, metadata.publishedDate)
        }

    private fun buildEpub(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zos ->
            entries.forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        val CONTAINER_XML =
            """
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
            """.trimIndent()
    }
}
