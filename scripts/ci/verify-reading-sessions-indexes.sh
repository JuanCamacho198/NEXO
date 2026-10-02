#!/usr/bin/env bash
# FR-13 verification-only check (correction C1): the two reading_sessions
# indexes MUST exist in migration 20261001000001. This script NEVER writes a
# migration; it fails if the indexes are absent so the gap is re-specified
# before any migration is written.
#
# Static check always runs. If `psql` and SUPABASE_DB_URL (or DATABASE_URL) are
# available, it also asserts both indexes exist on a live database.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATION="$ROOT/supabase/migrations/20261001000001_create_reading_sessions.sql"
INDEXES=("reading_sessions_user_date_idx" "reading_sessions_user_book_idx")

fail=0
for idx in "${INDEXES[@]}"; do
  if grep -qiE "create[[:space:]]+index[[:space:]]+if[[:space:]]+not[[:space:]]+exists[[:space:]]+${idx}" "$MIGRATION"; then
    echo "static: found ${idx} in $(basename "$MIGRATION")"
  else
    echo "static: MISSING ${idx} in $(basename "$MIGRATION")" >&2
    fail=1
  fi
done

DB_URL="${SUPABASE_DB_URL:-${DATABASE_URL:-}}"
if command -v psql >/dev/null 2>&1 && [ -n "$DB_URL" ]; then
  for idx in "${INDEXES[@]}"; do
    found="$(psql "$DB_URL" -tAc \
      "select 1 from pg_indexes where schemaname='public' and indexname='${idx}' limit 1" || true)"
    if [ "$found" = "1" ]; then
      echo "live: found ${idx}"
    else
      echo "live: MISSING ${idx}" >&2
      fail=1
    fi
  done
else
  echo "live: skipped (psql or SUPABASE_DB_URL/DATABASE_URL unavailable)"
fi

exit "$fail"
