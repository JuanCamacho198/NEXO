package com.nexo.data.remote.sync

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.nexo.data.sync.DriveFilename

/**
 * Drive file lookups scoped to a parent folder, shared by the storage data
 * source. Extracted so the readable-name fallback ([findByBookId]) lives next to
 * the exact-name lookup it backs, and so the data source stays focused on its
 * protocol (upload/download/list).
 */
internal class DriveFileLookup(
    private val driveService: Drive,
) {
    /**
     * Find a (non-trashed) file by name within the given parent folder.
     */
    fun findByName(
        folderId: String,
        name: String,
    ): File? {
        val query = "name='$name' and '$folderId' in parents and trashed = false"
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name)")
                .execute()
        return files.files?.firstOrNull { it.name == name }
    }

    /**
     * Like [findByName] but requests `appProperties` so the guard marker can be
     * read without a second `files.get` round-trip.
     */
    fun findByNameWithProperties(
        folderId: String,
        name: String,
    ): File? {
        val query = "name='$name' and '$folderId' in parents and trashed = false"
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name, appProperties)")
                .execute()
        return files.files?.firstOrNull { it.name == name }
    }

    /**
     * Fallback lookup for a book whose Drive object is stored under the readable
     * new-upload name (`<title> -- <id>.<ext>`, commit a405f987) while the caller
     * only holds the canonical logical path (`books/{user}/{id}.{ext}`).
     *
     * A readable name always ENDS with the id-bearing token, so an exact-name
     * miss is retried with `name contains '<id>.'` and the result is filtered to
     * a name that actually ends with `<id>.<ext>`. That excludes both a sibling
     * book whose id merely contains this one as a prefix and the id-only twin
     * (which the exact match would already have found, so reaching here means it
     * does not exist). Returns null when nothing matches — the caller then
     * reports REMOTE_NOT_FOUND.
     */
    fun findByBookId(
        folderId: String,
        path: String,
    ): File? {
        val parsed = DriveFilename.parseDrivePath(path) ?: return null
        val suffix = "${parsed.stem}.${parsed.extension}"
        val query = "name contains '${parsed.stem}.' and '$folderId' in parents and trashed = false"
        val files =
            driveService
                .files()
                .list()
                .setSpaces("drive")
                .setQ(query)
                .setFields("files(id, name)")
                .execute()
        return files.files?.firstOrNull { it.name?.endsWith(suffix) == true }
    }
}
