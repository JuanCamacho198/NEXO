#!/usr/bin/env bash
# Self-test for the G2 progress single-writer audit (FR-10, WU2a).
#
# A gate that cannot fail is not a gate. This asserts, reproducibly in CI:
#   * every writer fixture committed under fixtures/progress-audit/ IS caught
#     (the audit exits 1) — including the multi-line INSERT column list and the
#     setter-style mutation the line-based scan used to miss; and
#   * the real repository tree still PASSES (the audit exits 0).
#
# Runs the same way with or without ripgrep: the multi-line case is detected by
# the audit's portable statement-normalization pass, not by a multiline regex.
#
# Exit 0 = the audit detects every known writer and clears the real tree.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUDIT="$HERE/progress-single-writer-audit.sh"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIXTURES="$HERE/fixtures/progress-audit"

if [ ! -f "$AUDIT" ]; then
  echo "self-test: audit script not found at $AUDIT" >&2
  exit 1
fi

status=0
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

shopt -s nullglob
fixture_dirs=("$FIXTURES"/*/)
if [ ${#fixture_dirs[@]} -eq 0 ]; then
  echo "self-test: no fixtures under $FIXTURES" >&2
  exit 1
fi

for dir in "${fixture_dirs[@]}"; do
  name="$(basename "$dir")"
  if AUDIT_ROOT="$dir" "${BASH:-bash}" "$AUDIT" >"$tmp" 2>&1; then
    echo "FAIL: fixture '$name' was NOT caught (audit exited 0)"
    sed 's/^/       /' "$tmp"
    status=1
  else
    echo "OK:   fixture '$name' caught (audit exited 1)"
  fi
done

if AUDIT_ROOT="$REPO_ROOT" "${BASH:-bash}" "$AUDIT" >"$tmp" 2>&1; then
  echo "OK:   real tree passed (audit exited 0)"
else
  echo "FAIL: real tree was flagged by the audit"
  sed 's/^/       /' "$tmp"
  status=1
fi

if [ "$status" -ne 0 ]; then
  echo
  echo "audit self-test FAILED"
  exit 1
fi

echo
echo "audit self-test PASSED (${#fixture_dirs[@]} fixtures caught, real tree clean)."
