# Build the stable desktop updater feed from a per-version latest.json.
#
# Exactly two transformations, nothing else:
#
#   1. Every platforms.<target>.url is translated through $assets, a map of
#      API asset URL -> browser download URL built from the same release. A
#      plain GET on the API form returns the asset METADATA (application/json),
#      not the binary; a plain GET on the browser form returns the binary. A
#      URL that is already in browser form, or absent from the map, is copied
#      through untouched.
#   2. notes is guaranteed non-empty. The desktop client's parseDesktopFeed
#      rejects an empty notes as malformed, so a feed with "" would never offer
#      an update; release-please can create a desktop release with an empty
#      body, which is exactly how the shipped feeds ended up with "".
#
# Every other field - version, pub_date, channel, and every platforms.<t>.signature -
# is copied verbatim. Signatures are never touched.
#
# Input:  a versioned desktop latest.json on stdin.
# Args:   --slurpfile assets <map.json>   one document: { "<api url>": "<browser url>" }
#         --arg notes_fallback "<text>"   used only when notes is empty.
.platforms |=
  with_entries(
    if (.value | type == "object" and has("url"))
    then .value.url = (.value.url as $u | ($assets[0][$u] // $u))
    else .
    end
  )
| if (.notes | type == "string" and length > 0) then .
  else .notes = $notes_fallback
  end
