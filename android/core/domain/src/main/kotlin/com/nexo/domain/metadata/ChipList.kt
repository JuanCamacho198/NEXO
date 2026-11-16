package com.nexo.domain.metadata

/**
 * Parses a comma-separated metadata field (genre/tags stored as TEXT, design D2)
 * into its individual values, trimmed and with blanks dropped.
 */
fun parseChipList(value: String?): List<String> =
    value
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

/**
 * Sanitizes a single user-entered chip value: strips embedded commas, trims,
 * and rejects values that become empty (REQ-data-model-8).
 */
fun sanitizeChipValue(value: String): String? = value.replace(",", "").trim().takeIf { it.isNotEmpty() }

/**
 * Sanitizes a chip list: drops empties, removes case-insensitive duplicates
 * while keeping the first occurrence, and caps the result at [max] (REQ-data-model-8).
 */
fun sanitizeChipList(
    values: List<String>,
    max: Int,
): List<String> =
    values
        .mapNotNull { sanitizeChipValue(it) }
        .distinctBy { it.lowercase() }
        .take(max)
