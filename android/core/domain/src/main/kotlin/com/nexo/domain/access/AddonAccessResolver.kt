package com.nexo.domain.access

import com.nexo.domain.model.CatalogBook

/**
 * Resolves one installed addon's reading links for a book.
 *
 * An interface rather than a raw lambda so the binding has a real type Dagger
 * can key on, and so callers depend on intent instead of a function shape.
 */
interface AddonAccessResolver {
    suspend fun resolve(
        addonId: String,
        book: CatalogBook,
    ): LegalAccess
}
