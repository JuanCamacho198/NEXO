#!/usr/bin/env bash
# Publish the stable, versionless desktop updater feed.
#
# Reads the per-version desktop-v<version>/latest.json (the historical record,
# never modified), rewrites its platform URLs from API asset URLs to browser
# download URLs, and uploads the result as latest.json on the dedicated
# `desktop-latest` release with --clobber. The updater endpoint baked into every
# desktop build points at that stable release, so it survives version bumps.
#
# `desktop-latest` is created as a PRERELEASE the first time this runs. That
# keeps it off the repository "Latest" badge and keeps releases/latest from ever
# resolving to it (releases/latest must not be used: android and desktop
# releases are both non-prerelease, so latest can resolve to the Android
# release, which carries no latest.json).
#
# Run this only after all three desktop matrix legs have uploaded their platform
# keys to the versioned feed. It refuses to publish a feed missing a platform
# family.
#
# Usage: publish-desktop-updater-feed.sh <desktop-vX.Y.Z> [--dry-run <path>]
#   --dry-run writes the rewritten feed to <path> and skips release create/upload.
#
# Env:
#   GITHUB_REPOSITORY  owner/repo (defaults to JuanCamacho198/NEXO)
#   STABLE_TAG         stable release tag (defaults to desktop-latest)
#   TARGET_SHA         commit to point the desktop-latest tag at on first create
set -euo pipefail

usage() {
  echo "usage: publish-desktop-updater-feed.sh <desktop-vX.Y.Z> [--dry-run <path>]" >&2
  exit 2
}

if [ "$#" -lt 1 ] || [ "$#" -gt 3 ]; then usage; fi
TAG="$1"
DRY_RUN_PATH=""
if [ "$#" -eq 3 ]; then
  if [ "$2" != "--dry-run" ]; then usage; fi
  DRY_RUN_PATH="$3"
fi

REPO="${GITHUB_REPOSITORY:-JuanCamacho198/NEXO}"
STABLE_TAG="${STABLE_TAG:-desktop-latest}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JQ_FILTER="${SCRIPT_DIR}/build-desktop-updater-feed.jq"

command -v gh >/dev/null 2>&1 || { echo "::error::gh is required" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "::error::jq is required" >&2; exit 1; }

VERSION="${TAG#desktop-v}"
if [ "${VERSION}" = "${TAG}" ]; then
  echo "::error::expected a desktop-v* tag, got '${TAG}'" >&2
  exit 1
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

echo "Fetching ${TAG}/latest.json from ${REPO}"
if ! gh release download "${TAG}" --repo "${REPO}" --pattern latest.json \
  --output "${WORKDIR}/versioned.json" --clobber; then
  echo "::error::${TAG} has no latest.json asset; cannot build the stable feed" >&2
  exit 1
fi

# The versioned feed is assembled by merging platform keys across the three
# matrix legs. Publishing before all three report would ship a feed missing a
# platform, so refuse an incomplete input outright.
for family in windows darwin linux; do
  if ! jq -e --arg p "^${family}-" '[.platforms | keys[] | select(test($p))] | length > 0' \
    "${WORKDIR}/versioned.json" >/dev/null; then
    echo "::error::${TAG}/latest.json has no ${family} platform entry; refusing to publish a partial stable feed" >&2
    exit 1
  fi
done

echo "Building API-url -> browser-download-url map from ${TAG} assets"
gh release view "${TAG}" --repo "${REPO}" --json assets \
  --jq '[.assets[] | {key: .apiUrl, value: .url}] | from_entries' > "${WORKDIR}/assets.json"

NOTES_FALLBACK="Desktop version ${VERSION} is available. Download and install to update."

echo "Rewriting platform URLs (signatures preserved verbatim)"
jq --slurpfile assets "${WORKDIR}/assets.json" \
  --arg notes_fallback "${NOTES_FALLBACK}" \
  -f "${JQ_FILTER}" "${WORKDIR}/versioned.json" > "${WORKDIR}/latest.json"

# Fail loud rather than publish a feed whose URLs still return asset metadata.
if ! jq -e '[.platforms[].url | select((startswith("https://github.com/") and contains("/releases/download/")) | not)] | length == 0' \
  "${WORKDIR}/latest.json" >/dev/null; then
  echo "::error::rewritten feed still contains non browser-download URLs (an asset had no matching release asset):" >&2
  jq -r '.platforms[].url | select((startswith("https://github.com/") and contains("/releases/download/")) | not)' \
    "${WORKDIR}/latest.json" >&2
  exit 1
fi

if [ -n "${DRY_RUN_PATH}" ]; then
  cp "${WORKDIR}/latest.json" "${DRY_RUN_PATH}"
  echo "Dry run: wrote ${DRY_RUN_PATH} (no release created or uploaded)"
  exit 0
fi

FEED_NOTES="Stable, versionless desktop updater feed consumed by the in-app updater.

This release holds a single asset, latest.json, re-uploaded with --clobber by release-builds.yml after every desktop-v* release. It is marked prerelease on purpose: that keeps it off the repository \"Latest\" badge and keeps releases/latest from resolving to it.

Forward-only note: automatic updates only work for desktop builds released after the updater endpoint was repointed at this feed. Older installed builds cannot update themselves and must re-download an installer once."

if gh release view "${STABLE_TAG}" --repo "${REPO}" >/dev/null 2>&1; then
  echo "Updating existing ${STABLE_TAG} release metadata"
  gh release edit "${STABLE_TAG}" --repo "${REPO}" \
    --prerelease \
    --title "Desktop updater feed (stable)" \
    --notes "${FEED_NOTES}"
else
  echo "Creating ${STABLE_TAG} release (prerelease)"
  gh release create "${STABLE_TAG}" --repo "${REPO}" \
    --prerelease \
    --title "Desktop updater feed (stable)" \
    --notes "${FEED_NOTES}" \
    --target "${TARGET_SHA:-${GITHUB_SHA:-main}}"
fi

gh release upload "${STABLE_TAG}" "${WORKDIR}/latest.json" --clobber --repo "${REPO}"
echo "Published ${STABLE_TAG}/latest.json (desktop version ${VERSION}) from ${TAG}"
