package com.nexo.data.sync

/**
 * WU6 Drive layout migration decision for the shared book folder. Mirrors the
 * desktop `driveLayoutMigration` plan so both clients reach `Nexo/books`.
 *
 * The migration is gated on the remote `manifest.json` `filenameVersion >= 1`
 * so the layout never migrates while the retired filename sanitizers can still
 * cause new Drive collisions. The gate only matters when there is a legacy tree:
 * a fresh install has no legacy folder and always creates the canonical one.
 *
 * `Books` (capitalized legacy) is renamed IN PLACE via `files.update` on the
 * same folder id, so a found folder is never shadowed by a duplicate create and
 * no child is lost. If both names exist (Drive allows case-different siblings)
 * the canonical folder wins and the legacy twin is left untouched.
 */
object DriveBooksFolder {
    const val CANONICAL = "books"
    const val LEGACY = "Books"
    const val MIN_FILENAME_VERSION = 1

    sealed interface Plan {
        data class Adopt(
            val folderId: String,
        ) : Plan

        data class Rename(
            val folderId: String,
        ) : Plan

        data class Blocked(
            val folderId: String,
        ) : Plan

        data object Create : Plan
    }

    fun shouldMigrate(filenameVersion: Int): Boolean = filenameVersion >= MIN_FILENAME_VERSION

    fun plan(
        canonicalId: String?,
        legacyId: String?,
        filenameVersion: Int,
    ): Plan =
        when {
            canonicalId != null -> Plan.Adopt(canonicalId)
            legacyId == null -> Plan.Create
            !shouldMigrate(filenameVersion) -> Plan.Blocked(legacyId)
            else -> Plan.Rename(legacyId)
        }

    /** Folder a read-only caller should use (canonical first, legacy fallback). */
    fun pickFolder(
        canonicalId: String?,
        legacyId: String?,
    ): String? = canonicalId ?: legacyId
}
