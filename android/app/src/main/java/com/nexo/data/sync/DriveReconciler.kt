package com.nexo.data.sync

import com.nexo.data.remote.sync.StorageSyncRemoteDataSource

/**
 * FR-07 adopt-or-rename reconciliation (Phase A only). For every library book
 * the reconciler computes the canonical Drive name plus every legacy form a
 * pre-WU4 client could have written, scans the remote listing, and emits a plan:
 *
 * - canonical object present, no legacy twin -> adopt it (record it, no write);
 * - canonical object present + legacy twin    -> keep canonical live; the twin
 *   is left untouched (never deleted). A differing checksum flags the outcome
 *   `FlagMismatch` for observability;
 * - legacy-only object                        -> copy bytes to the canonical
 *   name, verify, and LEAVE THE SOURCE IN PLACE (Phase A never deletes; Phase B
 *   parking is deferred behind the `legacyParked` cutover flag in the design).
 *
 * The plan never contains a delete. Reconciliation cannot orphan or duplicate a
 * book: exactly one canonical target is resolvable per book.
 */
object DriveReconciler {
    /** One library book that needs a canonical remote name. */
    data class BookRef(
        val bookId: String,
        val extension: String,
    )

    /** One remote book object, identified by its physical file name. */
    data class DriveObject(
        val name: String,
        val checksum: String? = null,
    )

    sealed interface Action {
        data class Adopt(
            val bookId: String,
            val canonicalName: String,
        ) : Action

        data class KeepCanonical(
            val bookId: String,
            val canonicalName: String,
            val legacyTwin: String,
        ) : Action

        data class FlagMismatch(
            val bookId: String,
            val canonicalName: String,
            val legacyTwin: String,
        ) : Action

        data class CopyToCanonical(
            val bookId: String,
            val sourceName: String,
            val canonicalName: String,
        ) : Action
    }

    data class Failure(
        val source: String,
        val reason: String,
    )

    data class Outcome(
        val actions: List<Action>,
        val copied: List<String>,
        val failed: List<Failure>,
    )

    private const val STATE_SUFFIX = "_state.json"

    /** Pure plan: no I/O, no deletes. */
    fun plan(
        books: List<BookRef>,
        objects: List<DriveObject>,
    ): List<Action> {
        val actions = mutableListOf<Action>()
        for (book in books) {
            val canonicalName = DriveFilename.objectName(book.bookId, book.extension)
            val legacyStems = DriveFilename.legacyForms(book.bookId)
            val legacyTwin =
                objects.firstOrNull { candidate ->
                    candidate.name != canonicalName &&
                        stemOf(candidate.name)?.let { it in legacyStems } == true
                }
            val canonicalPresent = objects.any { it.name == canonicalName }

            when {
                canonicalPresent && legacyTwin == null ->
                    actions += Action.Adopt(book.bookId, canonicalName)
                canonicalPresent && legacyTwin != null -> {
                    val canonical = objects.first { it.name == canonicalName }
                    val mismatch =
                        canonical.checksum != null &&
                            legacyTwin.checksum != null &&
                            canonical.checksum != legacyTwin.checksum
                    actions +=
                        if (mismatch) {
                            Action.FlagMismatch(book.bookId, canonicalName, legacyTwin.name)
                        } else {
                            Action.KeepCanonical(book.bookId, canonicalName, legacyTwin.name)
                        }
                }
                legacyTwin != null ->
                    actions +=
                        Action.CopyToCanonical(
                            bookId = book.bookId,
                            sourceName = legacyTwin.name,
                            canonicalName = canonicalName,
                        )
            }
        }
        return actions
    }

    /**
     * Execute the plan under [prefix]. Copy-to-canonical downloads the source,
     * uploads the canonical target, then re-downloads it and verifies the bytes.
     * A failed verification is reported and the source is preserved; nothing is
     * ever deleted.
     */
    suspend fun reconcile(
        dataSource: StorageSyncRemoteDataSource,
        prefix: String,
        books: List<BookRef>,
    ): Outcome {
        val remotePaths = dataSource.list(prefix)
        val objects = remotePaths.map { DriveObject(physicalName(it)) }
        val actions = plan(books, objects)
        val copied = mutableListOf<String>()
        val failed = mutableListOf<Failure>()

        for (action in actions) {
            if (action !is Action.CopyToCanonical) continue
            try {
                val sourceBytes = dataSource.download(prefix + action.sourceName)
                dataSource.upload(prefix + action.canonicalName, sourceBytes)
                val verifyBytes = dataSource.download(prefix + action.canonicalName)
                if (verifyBytes.contentEquals(sourceBytes)) {
                    copied += action.canonicalName
                } else {
                    failed += Failure(action.sourceName, "canonical verification mismatch")
                }
            } catch (error: Exception) {
                failed += Failure(action.sourceName, error.message ?: "copy failed")
            }
        }

        return Outcome(actions = actions, copied = copied, failed = failed)
    }

    private fun physicalName(path: String): String = path.substringAfterLast('/')

    private fun stemOf(objectName: String): String? {
        if (objectName.isEmpty() || objectName.endsWith(STATE_SUFFIX)) return null
        val dot = objectName.lastIndexOf('.')
        if (dot <= 0 || dot == objectName.lastIndex) return null
        return objectName.substring(0, dot)
    }
}
