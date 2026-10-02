#!/usr/bin/env bash
# G2 gate for storage-layout-and-sync WU2a (FR-10).
#
# Proves the pre-drop invariant for reading position:
#   * zero intentional writers of books.progress_percentage outside the backfill
#   * every reader of that column is inventoried (canonical path or documented fallback)
#
# The DB-level backfill count (== 0) is asserted by
# android/app/src/test/java/com/nexo/data/sync/ProgressReconcilerTest.kt
# (backfill_divergent_cacheOnly_canonicalOnly_preservesEveryPosition).
#
# Scope: every root where a writer could hide — the Android app sources (DAOs,
# entities, migrations), the desktop Rust sources, and both SQL migration trees
# (desktop/src-tauri/migrations, supabase). A desktop- or migration-only
# regression must not pass unnoticed.
#
# AUDIT_ROOT overrides the scanned root (used by the self-test that injects a
# fake writer into a fixture tree). Default: this repository.
#
# Exit 0 = gate green. Exit 1 = a writer was found (hard failure).
set -euo pipefail

ROOT="${AUDIT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$ROOT"

ROOTS=(
  "android/app/src/main"
  "desktop/src-tauri/src"
  "desktop/src-tauri/migrations"
  "supabase"
)
EXCLUDES=(--glob '!**/schemas/**' --glob '!**/build/**' --glob '!**/target/**')

# A writer is any occurrence of the field on the left side of an assignment
# (snake_case SQL/Kotlin or camelCase Kotlin), or any upsert/insert statement
# that carries the column. `\b` keeps `avg_progress_percentage` (a read alias)
# from being mistaken for the cache column.
WRITER_PATTERN='(\bprogress_percentage[[:space:]]*=[^=])'
WRITER_PATTERN+='|(progressPercentage[[:space:]]*=[^=])'
WRITER_PATTERN+='|((INSERT|insert|upsert|Upsert|UPSERT)[^;]*(progress_percentage|progressPercentage))'

# Mappers that copy the field from itself or from the canonical progress source
# introduce no new position; they are readers in the single-writer inventory, so
# they are excluded from the camelCase failure set.
CAMEL_READER_ALLOW='progressPercentage[[:space:]]*=[[:space:]]*(progressPercentage|canonical\.percentage)[[:space:]]*,'

if command -v rg >/dev/null 2>&1; then
  SEARCH() { rg -n "$@"; }
else
  # Fallback: grep -rE, no glob excludes (schemas are JSON and match the writer
  # pattern only inside a createSql string, which is intentionally not on disk
  # SQL; to stay honest, exclude them explicitly with find).
  SEARCH() {
    local pattern="$1"; shift
    local dirs=()
    for d in "$@"; do [ -d "$d" ] && dirs+=("$d"); done
    [ ${#dirs[@]} -eq 0 ] && return 0
    grep -rnE "$pattern" "${dirs[@]}" \
      | grep -v '/schemas/' \
      | grep -v '/build/' \
      | grep -v '/target/' || true
  }
fi

echo "== G2: intentional writers of books.progress_percentage (must be none) =="
WRITERS=""
if command -v rg >/dev/null 2>&1; then
  WRITERS="$(rg -n "$WRITER_PATTERN" "${ROOTS[@]}" "${EXCLUDES[@]}" | grep -vE "$CAMEL_READER_ALLOW" || true)"
else
  WRITERS="$(SEARCH "$WRITER_PATTERN" "${ROOTS[@]}" | grep -vE "$CAMEL_READER_ALLOW" || true)"
fi

if [ -n "$WRITERS" ]; then
  echo "FAIL: intentional writer(s) of books.progress_percentage found:"
  echo "$WRITERS"
  echo
  echo "WU2a requires single-writer: reading_progress.percentage only."
  exit 1
fi
echo "OK: no intentional writer of books.progress_percentage outside the backfill."

echo
echo "== Reader inventory of books.progress_percentage (informational) =="
if command -v rg >/dev/null 2>&1; then
  rg -n 'progress_percentage|progressPercentage' "${ROOTS[@]}" "${EXCLUDES[@]}" || true
else
  SEARCH 'progress_percentage|progressPercentage' "${ROOTS[@]}" || true
fi

echo
echo "== G2 backfill invariant (DB-level, asserted by ProgressReconcilerTest) =="
cat <<'SQL'
Zero positions representable ONLY in books.progress_percentage means:
  1) No cache>0 row lacks a canonical row:
       SELECT COUNT(*) FROM books b
        WHERE b.progress_percentage > 0
          AND NOT EXISTS (SELECT 1 FROM reading_progress rp WHERE rp.book_id = b.id);
  2) No cache row with a KNOWN timestamp is strictly newer than its canonical row:
       SELECT COUNT(*) FROM books b
         JOIN reading_progress rp ON rp.book_id = b.id
        WHERE b.progress_percentage > 0
          AND b.progress_updated_at IS NOT NULL
          AND b.progress_updated_at > rp.updated_at;
  3) A NULL cache timestamp is unknown provenance: after backfill, a cache row
     carrying progress must equal its canonical row (the cache value won):
       SELECT COUNT(*) FROM books b
         JOIN reading_progress rp ON rp.book_id = b.id
        WHERE b.progress_percentage > 0
          AND b.progress_updated_at IS NULL
          AND rp.percentage <> b.progress_percentage;
  All counts MUST be 0 after the WU2a backfill (G2).
SQL

echo
echo "G2 audit PASSED."
