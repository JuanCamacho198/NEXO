package com.nexo.domain.model

/**
 * Signals that an EPUB import would duplicate a book already present in the
 * library. Raised by [com.nexo.domain.repository.LibraryRepository] and
 * mapped by the download/import flow to a non-error "already in library"
 * outcome rather than a failure.
 */
class DuplicateBookException(
    message: String = "Book already in the library",
) : Exception(message)
