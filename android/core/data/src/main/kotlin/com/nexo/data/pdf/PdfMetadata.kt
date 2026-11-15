package com.nexo.data.pdf

data class PdfMetadata(
    val title: String?,
    val author: String?,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val coverBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfMetadata) return false
        return title == other.title &&
            author == other.author &&
            pageCount == other.pageCount &&
            fileSizeBytes == other.fileSizeBytes &&
            coverBytes.contentEquals(other.coverBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + (author?.hashCode() ?: 0)
        result = 31 * result + pageCount
        result = 31 * result + (fileSizeBytes xor (fileSizeBytes ushr 32)).toInt()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}
