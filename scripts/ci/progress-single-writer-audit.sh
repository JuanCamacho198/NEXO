#!/usr/bin/env bash
# G2 gate for storage-layout-and-sync WU2a (FR-10).
#
# Proves the pre-drop invariant for reading position:
#   * zero intentional writers of books.progress_percentage outside the backfill
#   * every reader of that column is inventoried (canonical path or documented fallback)
#
# The DB-level backfill count (== 0) is asserted by the WU2b migration test
# android/app/src/test/java/com/nexo/data/local/AppDatabaseMigrationTest.kt
# (`migration 29 to 30 backfills every divergent position before dropping the
# cache column`).
#
# Scope: every root where a writer could hide — the Android app sources (DAOs,
# entities, migrations), the desktop Rust sources, and both SQL migration trees
# (desktop/src-tauri/migrations, supabase). A desktop- or migration-only
# regression must not pass unnoticed.
#
# AUDIT_ROOT overrides the scanned root. The committed self-test
# (progress-single-writer-audit-selftest.sh) points it at each fixture tree in
# scripts/ci/fixtures/progress-audit/ and asserts every fixture is caught while
# the real repository passes. Default: this repository.
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

# Fixture trees carry only the one root a case needs, so scan the roots that
# exist instead of failing on a missing directory.
SCAN_ROOTS=()
for d in "${ROOTS[@]}"; do
  [ -d "$d" ] && SCAN_ROOTS+=("$d")
done
if [ ${#SCAN_ROOTS[@]} -eq 0 ]; then
  echo "FAIL: none of the scanned roots exist under $ROOT" >&2
  exit 1
fi

# A writer is any occurrence of the field on the left side of an assignment
# (snake_case SQL/Kotlin or camelCase Kotlin), any upsert/insert statement that
# carries the column, or a setter-style mutation such as
# `setProgressPercentage(5f)` / `set_progress_percentage(5f)`. `\b` keeps
# `avg_progress_percentage` (a read alias) from being mistaken for the cache
# column.
WRITER_PATTERN='(\bprogress_percentage[[:space:]]*=[^=])'
WRITER_PATTERN+='|(progressPercentage[[:space:]]*=[^=])'
WRITER_PATTERN+='|((INSERT|insert|upsert|Upsert|UPSERT)[^;]*(progress_percentage|progressPercentage))'
WRITER_PATTERN+='|([sS]et[_]?[pP]rogress[_]?[pP]ercentage[[:space:]]*\()'

# Mappers that copy the field from itself or from the canonical progress source
# introduce no new position; they are readers in the single-writer inventory, so
# they are excluded from the camelCase failure set.
CAMEL_READER_ALLOW='progressPercentage[[:space:]]*=[[:space:]]*(progressPercentage|canonical\.percentage)[[:space:]]*,'

if command -v rg >/dev/null 2>&1; then
  SEARCH() { rg -n "$@"; }
  # `rg --files` avoids the Git-Bash-on-Windows `find` name collision with the
  # Windows FIND.EXE that shows up in a non-login shell, and honors the same
  # excludes. Backslashes are normalized to `/` because on Windows rg emits
  # `dir\file` and the awk pass below could not open those paths.
  LIST_FILES() { rg --files "${SCAN_ROOTS[@]}" "${EXCLUDES[@]}" 2>/dev/null | tr '\\' '/' || true; }
else
  # Fallback: grep -rE, no glob excludes (schemas are JSON and match the writer
  # pattern only inside a createSql string, which is intentionally not on disk
  # SQL; to stay honest, exclude them explicitly).
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
  LIST_FILES() {
    find "${SCAN_ROOTS[@]}" -type f \
      -not -path '*/schemas/*' -not -path '*/build/*' -not -path '*/target/*'
  }
fi

# Blind spot the line-based scan structurally cannot see: an INSERT whose column
# list spans several physical lines (`progress_percentage` on its own line, the
# keyword and INTO on others). rg and grep are line-oriented, so collapse each
# file to one logical statement per line (append lines, split at `;`) with a
# single portable awk pass, then check only the INSERT *target column list* for
# the column. A writer is `progress_percentage` inside the parenthesized list
# that follows `INTO <table>`; anything after `SELECT` is a read, not a write —
# the WU2b DROP migration legitimately reads the column it is about to drop
# (backfill + verification), and flagging that read would be a false positive.
# Works identically with or without ripgrep.
multiline_insert_writers() {
  LIST_FILES | xargs -d '\n' -r awk '
    function check(s,    lower, rest, p, start, tail, into, openPos, closePos, cols) {
      lower = tolower(s)
      p = 1
      while (1) {
        rest = substr(lower, p)
        if (index(rest, "insert") == 0) return
        start = p + index(rest, "insert") - 1
        tail = substr(lower, start)
        into = index(tail, "into")
        if (into > 0) {
          openPos = index(substr(tail, into), "(")
          if (openPos > 0) {
            closePos = index(substr(tail, into + openPos), ")")
            if (closePos > 0) {
              cols = substr(tail, into + openPos, closePos - 1)
              if (cols ~ /progress_percentage/) print file ": " s
            }
          }
        }
        p = start + 5
      }
    }
    FNR == 1 { check(stmt); stmt = ""; file = FILENAME }
    {
      stmt = stmt " " $0
      while ((i = index(stmt, ";")) > 0) {
        check(substr(stmt, 1, i - 1))
        stmt = substr(stmt, i + 1)
      }
    }
    END { check(stmt) }
  ' 2>/dev/null || true
}

echo "== G2: intentional writers of books.progress_percentage (must be none) =="
WRITERS=""
if command -v rg >/dev/null 2>&1; then
  WRITERS="$(rg -n "$WRITER_PATTERN" "${SCAN_ROOTS[@]}" "${EXCLUDES[@]}" | grep -vE "$CAMEL_READER_ALLOW" || true)"
else
  WRITERS="$(SEARCH "$WRITER_PATTERN" "${SCAN_ROOTS[@]}" | grep -vE "$CAMEL_READER_ALLOW" || true)"
fi

MULTILINE="$(multiline_insert_writers)"
if [ -n "$MULTILINE" ]; then
  if [ -n "$WRITERS" ]; then
    WRITERS="$WRITERS
$MULTILINE"
  else
    WRITERS="$MULTILINE"
  fi
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
  rg -n 'progress_percentage|progressPercentage' "${SCAN_ROOTS[@]}" "${EXCLUDES[@]}" || true
else
  SEARCH 'progress_percentage|progressPercentage' "${SCAN_ROOTS[@]}" || true
fi

echo
echo "== G2 backfill invariant (DB-level, asserted by the WU2b 29->30 migration test) =="
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
  All counts MUST be 0 after the WU2b DROP migration's in-migration backfill (G2).
SQL

echo
echo "G2 audit PASSED."
