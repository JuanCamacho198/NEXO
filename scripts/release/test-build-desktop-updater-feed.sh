#!/usr/bin/env bash
# Focused unit test for build-desktop-updater-feed.jq.
#
# Proves, without any network or release: an API asset URL becomes a browser
# download URL, an already-correct URL is left alone, every signature and every
# other field survives byte-for-byte, an empty notes is backfilled, and the
# transform is deterministic.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILTER="${SCRIPT_DIR}/build-desktop-updater-feed.jq"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

fail=0
fails=()
note() { echo "FAIL: $*"; fails+=("$*"); fail=1; }
assert_eq() {
  if [ "$2" != "$3" ]; then note "$1 (expected '$3', got '$2')"; fi
}

cat > "${WORKDIR}/assets.json" <<'JSON'
{
  "https://api.github.com/repos/JuanCamacho198/NEXO/releases/assets/111": "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_x64-setup.exe",
  "https://api.github.com/repos/JuanCamacho198/NEXO/releases/assets/222": "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_amd64.AppImage"
}
JSON

cat > "${WORKDIR}/input.json" <<'JSON'
{
  "version": "9.9.9",
  "notes": "First line.\nSecond line with \"quotes\".",
  "pub_date": "2026-10-01T00:00:00Z",
  "channel": "stable",
  "extra": { "kept": true },
  "platforms": {
    "windows-x86_64": { "url": "https://api.github.com/repos/JuanCamacho198/NEXO/releases/assets/111", "signature": "SIG-windows/==" },
    "linux-x86_64": { "url": "https://api.github.com/repos/JuanCamacho198/NEXO/releases/assets/222", "signature": "SIG-linux/==" },
    "darwin-aarch64": { "url": "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_universal.app.tar.gz", "signature": "SIG-darwin/==" }
  }
}
JSON

# Same shape but with the empty notes release-please produced for 0.3.2/0.3.3.
cat > "${WORKDIR}/input-empty-notes.json" <<'JSON'
{
  "version": "9.9.9",
  "notes": "",
  "pub_date": "2026-10-01T00:00:00Z",
  "channel": "stable",
  "platforms": {
    "windows-x86_64": { "url": "https://api.github.com/repos/JuanCamacho198/NEXO/releases/assets/111", "signature": "SIG-windows/==" }
  }
}
JSON

cat > "${WORKDIR}/expected.json" <<'JSON'
{
  "version": "9.9.9",
  "notes": "First line.\nSecond line with \"quotes\".",
  "pub_date": "2026-10-01T00:00:00Z",
  "channel": "stable",
  "extra": { "kept": true },
  "platforms": {
    "windows-x86_64": { "url": "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_x64-setup.exe", "signature": "SIG-windows/==" },
    "linux-x86_64": { "url": "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_amd64.AppImage", "signature": "SIG-linux/==" },
    "darwin-aarch64": { "url": "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_universal.app.tar.gz", "signature": "SIG-darwin/==" }
  }
}
JSON

run_filter() {
  jq --slurpfile assets "${WORKDIR}/assets.json" \
    --arg notes_fallback "Desktop version 9.9.9 is available." \
    -f "${FILTER}" "$1"
}
run_filter "${WORKDIR}/input.json" > "${WORKDIR}/actual.json"

# 1. The whole document matches the expected transform.
if ! diff -u <(jq -S . "${WORKDIR}/expected.json") <(jq -S . "${WORKDIR}/actual.json"); then
  note "rewritten feed does not match the expected document"
fi

# 2. API asset URLs became browser download URLs.
assert_eq "windows url rewritten" \
  "$(jq -r '.platforms["windows-x86_64"].url' "${WORKDIR}/actual.json")" \
  "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_x64-setup.exe"
assert_eq "linux url rewritten" \
  "$(jq -r '.platforms["linux-x86_64"].url' "${WORKDIR}/actual.json")" \
  "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_amd64.AppImage"

# 3. An already-correct URL is left alone.
assert_eq "already-correct url untouched" \
  "$(jq -r '.platforms["darwin-aarch64"].url' "${WORKDIR}/actual.json")" \
  "https://github.com/JuanCamacho198/NEXO/releases/download/desktop-v9.9.9/Nexo.Desktop_9.9.9_universal.app.tar.gz"

# 4. Every signature is byte-for-byte identical (key-order independent).
sig() { jq -c '[.platforms | to_entries[] | {key, signature: .value.signature}] | sort_by(.key)' "$1"; }
assert_eq "signatures preserved" "$(sig "${WORKDIR}/actual.json")" "$(sig "${WORKDIR}/input.json")"

# 5. Every other top-level field survives verbatim (version, pub_date, channel, extra, notes).
for field in version pub_date channel extra notes; do
  assert_eq "field '${field}' preserved" \
    "$(jq -c --arg f "${field}" '.[$f]' "${WORKDIR}/actual.json")" \
    "$(jq -c --arg f "${field}" '.[$f]' "${WORKDIR}/input.json")"
done

# 6. Deterministic: a second run is byte-identical.
run_filter "${WORKDIR}/input.json" > "${WORKDIR}/actual-again.json"
assert_eq "deterministic output" "$(cat "${WORKDIR}/actual-again.json")" "$(cat "${WORKDIR}/actual.json")"

# 7. An empty notes is backfilled (the client rejects "" as malformed).
run_filter "${WORKDIR}/input-empty-notes.json" > "${WORKDIR}/actual-empty-notes.json"
assert_eq "empty notes backfilled" \
  "$(jq -r '.notes' "${WORKDIR}/actual-empty-notes.json")" \
  "Desktop version 9.9.9 is available."

if [ "${fail}" -ne 0 ]; then
  echo "FAILED (${#fails[@]}):"
  for f in "${fails[@]}"; do echo "  - ${f}"; done
  exit 1
fi
echo "ALL FEED BUILDER TESTS PASS"
