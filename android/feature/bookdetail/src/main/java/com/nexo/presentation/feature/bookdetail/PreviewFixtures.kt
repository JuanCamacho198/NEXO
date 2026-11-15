package com.nexo.presentation.feature.bookdetail

import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingProgress

internal val previewBook =
    Book(
        id = "book-1",
        title = "Hábitos Atómicos",
        author = "James Clear",
        coverPath = null,
        filePath = "/books/atomic-habits.epub",
        format = "epub",
        totalPages = 320,
        chapterCount = 20,
        description = "Un sistema comprobado para construir buenos hábitos y eliminar los malos. Pequeños cambios, resultados extraordinarios.",
        genre = "Desarrollo personal",
        language = "es",
        publisher = "Penguin",
        tags = "favoritos, lectura-pendiente",
        publishedDate = "2018-10-16",
        userRating = 9,
        updatedAtEpochMillis = 1L,
    )

internal val previewProgress =
    ReadingProgress(
        id = "progress-1",
        bookId = "book-1",
        cfiLocation = "epubcfi(/6/10)",
        percentage = 32f,
        updatedAtEpochMillis = 1L,
    )
