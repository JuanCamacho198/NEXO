#Requires -Version 7.0
<#
.SYNOPSIS
    Regenerate the Android launcher icon and splash drawables from the brand
    export.

.DESCRIPTION
    The translation is done by `svg-vectordrawable` (CLI `svgvd`), a maintained
    SVG -> VectorDrawable converter that emits gradients as the `<aapt:attr>`
    blocks Android understands. An earlier hand-rolled translator in this repo
    emitted the SVG spelling (`fill="url(#id)"`, `pathData` without the
    `android:` prefix); it compiled and rendered NOTHING, which is why this
    script delegates instead of parsing SVG itself.

    Two crops of the same export, because the two slots frame differently:

      launcher   tile + ribbon + knocked-out "N", NO wordmark.
                 The tile is the icon's body and the launcher mask cuts its
                 corners, which is exactly how a full-bleed app icon works. The
                 ribbon then reads at its designed size inside the tile. The
                 mark-alone crop was tried first and the ribbon filled the whole
                 mask, which reads as an oversized glyph.

      splash     ribbon + "N" only, NO tile.
                 The Android 12 splash masks its icon to a circle of 2/3 the
                 drawable; the tile would be cropped into an odd disc, and the
                 splash background is already the tile's warm paper. Scaled 85%
                 and re-centred so the ribbon's tail tip stays inside the
                 guaranteed-visible circle.

      monochrome the splash crop, every paint flattened to white: Material You
                 uses the alpha channel as a mask.

      --width 200 --height 200
                 Android lint's VectorRaster check caps shipped vectors at
                 200dp. The viewport keeps the export's 512 units, so the
                 artwork is untouched; only the declared size changes.

.PARAMETER BrandSvg
    Source export. Defaults to brand/nexo-logo-inverso.svg.
#>
[CmdletBinding()]
param(
    [string] $BrandSvg = 'brand/nexo-logo-inverso.svg'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$source = Join-Path $repo $BrandSvg
$res = Join-Path $repo 'android/app/src/main/res/drawable'
$work = Join-Path $repo '.tmp/brand'

if (-not (Test-Path -LiteralPath $source)) { throw "Missing brand export: $source" }
$null = New-Item -ItemType Directory -Force -Path $work

$svg = Get-Content -LiteralPath $source -Raw

# The tile is the first two paths: its fill and its glow, both drawn under
# transform matrix(1 0 0 1 16 16).
$tilePattern = '<path transform="matrix\(1 0 0 1 16 16\)"[^/]*?/>'
$tileCount = ([regex]::Matches($svg, $tilePattern)).Count
if ($tileCount -ne 2) { throw "Expected 2 tile paths in $BrandSvg, found $tileCount. The export changed shape: update this script deliberately." }

# The wordmark is one group, from <g id="wordmark" ...> up to the <defs> block.
# The square app export has no wordmark (it is icon only); a combined lockup
# would, and dropping it keeps the launcher case correct for both.
$wmStart = $svg.IndexOf('<g id="wordmark"')
$wmEnd = $svg.IndexOf('<defs>')
if ($wmStart -ge 0 -and $wmEnd -gt $wmStart) {
    $launcher = $svg.Substring(0, $wmStart) + $svg.Substring($wmEnd)
} else {
    $launcher = $svg
}
$splash = [regex]::Replace($svg, $tilePattern, '')

$launcherPath = Join-Path $work 'launcher.svg'
$splashPath = Join-Path $work 'splash.svg'
Set-Content -LiteralPath $launcherPath -Value $launcher -Encoding utf8NoBOM
Set-Content -LiteralPath $splashPath -Value $splash -Encoding utf8NoBOM

# Material You reads the alpha channel, so the silhouette is the splash crop
# with every paint flattened to opaque white.
$silhouette = [regex]::Replace($splash, 'fill="(?:url\(#[^)]+\)|#[0-9A-Fa-f]{3,8})"', 'fill="#FFFFFF"')
$silhouette = [regex]::Replace($silhouette, 'stroke="(?:url\(#[^)]+\)|#[0-9A-Fa-f]{3,8})"', 'stroke="#FFFFFF"')
$silhouettePath = Join-Path $work 'splash-silhouette.svg'
Set-Content -LiteralPath $silhouettePath -Value $silhouette -Encoding utf8NoBOM

function Invoke-Svgvd([string] $inputPath, [string] $outputPath) {
    & bunx --bun svg-vectordrawable $inputPath -o $outputPath --width 200 --height 200 --xml-tag --strict=lossy
    if ($LASTEXITCODE -ne 0) { throw "svg-vectordrawable failed for $inputPath" }
}

Invoke-Svgvd $launcherPath (Join-Path $work 'launcher.xml')
Invoke-Svgvd $splashPath (Join-Path $work 'splash.xml')
Invoke-Svgvd $silhouettePath (Join-Path $work 'splash-silhouette.xml')

# A launcher only guarantees the central 66 of its 108 canvas. The splash crop is
# 359/512 = 70% of the viewport tall, so scale it to 85% and re-centre: that puts
# its furthest painted point inside the guaranteed circle.
function Add-SafeAreaWrapper([string] $xmlPath, [string] $targetPath) {
    $content = Get-Content -LiteralPath $xmlPath -Raw
    $open = [regex]::Match($content, '(?s)^(.*?<vector[^>]*>\r?\n)')
    if (-not $open.Success) { throw "No <vector> root in $xmlPath" }
    $head = $open.Groups[1].Value
    $body = $content.Substring($head.Length)
    $body = [regex]::Replace($body, '</vector>\s*$', '')
    $wrapped = $head +
    "    <group`n" +
    "        android:scaleX=""0.85""`n" +
    "        android:scaleY=""0.85""`n" +
    "        android:translateX=""38.4""`n" +
    "        android:translateY=""38.4"">`n" +
    $body +
    "    </group>`n</vector>`n"
    Set-Content -LiteralPath $targetPath -Value $wrapped -Encoding utf8NoBOM
}

# The launcher keeps the export's own framing: the tile fills the canvas and the
# mask does the cropping, exactly like any other full-bleed app icon.
Copy-Item -LiteralPath (Join-Path $work 'launcher.xml') -Destination (Join-Path $res 'ic_launcher_foreground.xml') -Force
Add-SafeAreaWrapper (Join-Path $work 'splash.xml') (Join-Path $res 'nexo_splash_mark.xml')
Add-SafeAreaWrapper (Join-Path $work 'splash-silhouette.xml') (Join-Path $res 'ic_launcher_monochrome.xml')

Write-Host 'Android brand drawables regenerated from' $BrandSvg
