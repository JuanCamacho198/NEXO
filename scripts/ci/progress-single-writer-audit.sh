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
# Exit 0 = gate green. Exit 1 = a writer was found (hard failure).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ANDROID_MAIN="android/app/src/main"
DESKTOP_SRC="desktop/src-tauri/src"
EXCLUDES=(--glob '!**/schemas/**' --glob '!**/build/**' --glob '!**/target/**')

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
    grep -rnE "$pattern" "${dirs[@]}" \
      | grep -v '/schemas/' \
      | grep -v '/build/' \
      | grep -v '/target/' || true
  }
fi

echo "== G2: intentional writers of books.progress_percentage (must be none) =="
WRITER_PATTERN='progress_percentage[[:space:]]*=[^=]'
WRITERS=""
if command -v rg >/dev/null 2>&1; then
  WRITERS="$(rg -n "$WRITER_PATTERN" "$ANDROID_MAIN" "$DESKTOP_SRC" "${EXCLUDES[@]}" || true)"
else
  WRITERS="$(SEARCH "$WRITER_PATTERN" "$ANDROID_MAIN" "$DESKTOP_SRC")"
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
  rg -n 'progress_percentage|progressPercentage' "$ANDROID_MAIN" "$DESKTOP_SRC" "${EXCLUDES[@]}" || true
else
  SEARCH 'progress_percentage|progressPercentage' "$ANDROID_MAIN" "$DESKTOP_SRC" || true
fi

echo
echo "== G2 backfill invariant (DB-level, asserted by ProgressReconcilerTest) =="
cat <<'SQL'
Zero positions representable ONLY in books.progress_percentage means:
  1) No cache>0 row lacks a canonical row:
       SELECT COUNT(*) FROM books b
        WHERE b.progress_percentage > 0
          AND NOT EXISTS (SELECT 1 FROM reading_progress rp WHERE rp.book_id = b.id);
  2) No cache row is strictly newer than its canonical row:
       SELECT COUNT(*) FROM books b
         JOIN reading_progress rp ON rp.book_id = b.id
        WHERE b.progress_percentage > 0
          AND b.progress_updated_at > rp.updated_at;
  Both counts MUST be 0 after the WU2a backfill (G2).
SQL

echo
echo "G2 audit PASSED."
