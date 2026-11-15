package com.nexo.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the [SyncOutboxEntity] contract.
 *
 * The `entity_id` column has FK `ON DELETE SET NULL` to `books(id)`, so when a
 * book is deleted SQLite nulls the column. The Kotlin field must therefore be
 * nullable — a non-nullable `String` here would crash with `IllegalStateException`
 * the moment Room tries to read the row.
 */
class SyncOutboxEntityTest {
    @Test
    fun entityId_isNullable_whenBookIsDeleted() {
        val item =
            SyncOutboxEntity(
                id = "out-1",
                entityType = SyncEntityType.BOOK.name,
                entityId = null,
                operation = SyncOperation.UPDATE.name,
                payloadJson = "{}",
                createdAtEpochMillis = 100L,
            )

        assertNull(item.entityId)
    }

    @Test
    fun entityId_isPreservedWhenNonNull() {
        val item =
            SyncOutboxEntity(
                id = "out-2",
                entityType = SyncEntityType.HIGHLIGHT.name,
                entityId = "book-42",
                operation = SyncOperation.UPDATE.name,
                payloadJson = "{}",
                createdAtEpochMillis = 200L,
            )

        assertEquals("book-42", item.entityId)
    }

    @Test
    fun equalityTreatsNullAndNonNullEntityIdAsDifferent() {
        val withBook =
            SyncOutboxEntity(
                id = "out-3",
                entityType = SyncEntityType.BOOK.name,
                entityId = "book-1",
                operation = SyncOperation.UPDATE.name,
                payloadJson = "{}",
                createdAtEpochMillis = 1L,
            )
        val withoutBook = withBook.copy(entityId = null)

        assertNotEquals(withBook, withoutBook)
    }
}
