#!/usr/bin/env bash
# Verify the stable desktop updater feed is published, reports the expected
# (newest) desktop version, covers all three platform families, and carries
# only browser download URLs. Cheap: one plain GET plus a few jq checks.
#
# Usage: check-desktop-updater-feed.sh <expected-version>
# Env:
#   GITHUB_REPOSITORY  owner/repo (defaults to JuanCamacho198/NEXO)
#   STABLE_TAG         stable release tag (defaults to desktop-latest)
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: check-desktop-updater-feed.sh <expected-version>" >&2
  exit 2
fi
EXPECTED="$1"

REPO="${GITHUB_REPOSITORY:-JuanCamacho198/NEXO}"
STABLE_TAG="${STABLE_TAG:-desktop-latest}"
FEED_URL="https://github.com/${REPO}/releases/download/${STABLE_TAG}/latest.json"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

if ! curl -fsSL "${FEED_URL}" -o "${WORKDIR}/latest.json"; then
  echo "::error::Stable desktop updater feed is missing or unreachable: ${FEED_URL}"
  exit 1
fi

ACTUAL="$(jq -r '.version // empty' "${WORKDIR}/latest.json")"
if [ -z "${ACTUAL}" ]; then
  echo "::error::Stable desktop updater feed has no version: ${FEED_URL}"
  exit 1
fi
if [ "${ACTUAL}" != "${EXPECTED}" ]; then
  echo "::error::Stable desktop updater feed reports version ${ACTUAL}, expected ${EXPECTED} (${FEED_URL})"
  exit 1
fi

BAD="$(jq -r '[.platforms[].url | select((startswith("https://github.com/") and contains("/releases/download/")) | not)] | unique | .[]' \
  "${WORKDIR}/latest.json")"
if [ -n "${BAD}" ]; then
  echo "::error::Stable desktop updater feed contains non browser-download platform URLs:"
  echo "${BAD}"
  exit 1
fi

for family in windows darwin linux; do
  if ! jq -e --arg p "^${family}-" '[.platforms | keys[] | select(test($p))] | length > 0' \
    "${WORKDIR}/latest.json" >/dev/null; then
    echo "::error::Stable desktop updater feed is missing a ${family} platform entry"
    exit 1
  fi
done

echo "Stable desktop updater feed OK: ${FEED_URL} reports ${ACTUAL} with all three platform families"
