package com.nexo.presentation.feature.editmetadata

import com.nexo.domain.model.Book

internal val previewBook =
    Book(
        id = "preview-book-1",
        title = "The Hobbit",
        author = "J.R.R. Tolkien",
        coverPath = null,
        filePath = "/preview/the-hobbit.epub",
        format = "epub",
        description = "A journey into Middle-earth.",
        updatedAtEpochMillis = 0L,
        genre = "Fantasy, Classics",
        language = "en",
        publisher = "George Allen & Unwin",
        tags = "favorites, adventure",
        publishedDate = "1937-09-21",
    )
