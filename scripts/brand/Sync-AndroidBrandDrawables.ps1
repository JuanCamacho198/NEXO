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

    The launcher icon is an adaptive icon with TWO layers, not one:

      background  the tile's warm gradient, full-bleed 0..512.
                  It must be the background layer, because a launcher masks
                  that layer to its own shape (circle, squircle, teardrop) and
                  the tile is exactly the surface the icon is supposed to be.

      foreground  the ribbon and its knocked-out "N", scaled to 78%.
                  A launcher only guarantees the central 66 of its 108 canvas.
                  The ribbon is 70% of the 512 viewport tall, so at full size
                  the mask crops its head and its tail: on a circular
                  launcher it reads as a glyph bursting out of the icon. At 78%
                  it is 55% tall, inside the safe circle with real breathing
                  room, and the tile still frames it.

      splash      the same foreground crop, on the tile's warm paper, scaled the
                  same way so the system splash and the app icon agree.

      monochrome  the foreground crop with every paint flattened to white:
                  Material You uses the alpha channel as a mask.

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
$mark = if ($wmStart -ge 0 -and $wmEnd -gt $wmStart) {
    [regex]::Replace($svg, $tilePattern, '')
} else {
    [regex]::Replace($svg, $tilePattern, '')
}

# --- background: the tile's gradient, full bleed ---------------------------
$tileGradient = [regex]::Match($svg, '<linearGradient id="paint2".*?</linearGradient>')
if (-not $tileGradient.Success) { throw "No tile gradient (paint2) in $source." }
$backgroundSvg = @"
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <defs>$($tileGradient.Value)</defs>
  <rect width="512" height="512" fill="url(#paint2)" />
</svg>
"@
$backgroundPath = Join-Path $work 'launcher-background.svg'
Set-Content -LiteralPath $backgroundPath -Value $backgroundSvg -Encoding utf8NoBOM

$markPath = Join-Path $work 'launcher-foreground.svg'
Set-Content -LiteralPath $markPath -Value $mark -Encoding utf8NoBOM

# Material You reads the alpha channel, so the silhouette is the same crop with
# every paint flattened to opaque white.
$silhouette = [regex]::Replace($mark, 'fill="(?:url\(#[^)]+\)|#[0-9A-Fa-f]{3,8})"', 'fill="#FFFFFF"')
$silhouette = [regex]::Replace($silhouette, 'stroke="(?:url\(#[^)]+\)|#[0-9A-Fa-f]{3,8})"', 'stroke="#FFFFFF"')
$silhouettePath = Join-Path $work 'launcher-silhouette.svg'
Set-Content -LiteralPath $silhouettePath -Value $silhouette -Encoding utf8NoBOM

function Invoke-Svgvd([string] $inputPath, [string] $outputPath) {
    & bunx --bun svg-vectordrawable $inputPath -o $outputPath --width 200 --height 200 --xml-tag --strict=lossy
    if ($LASTEXITCODE -ne 0) { throw "svg-vectordrawable failed for $inputPath" }
}

Invoke-Svgvd $backgroundPath (Join-Path $work 'launcher-background.xml')
Invoke-Svgvd $markPath (Join-Path $work 'launcher-foreground.xml')
Invoke-Svgvd $silhouettePath (Join-Path $work 'launcher-silhouette.xml')

# A launcher guarantees only the central 66 of its 108 canvas, so the mark is
# scaled to 78% (55% of the canvas) and re-centred: that keeps the ribbon's head
# and tail inside the safe circle on every mask.
#
# The splash needs a different scale for the same apparent size. The platform
# masks a splash icon to a circle 2/3 of the drawable, so the visible area is
# only 67% of what the launcher shows. At 78% the ribbon filled 80% of that
# circle; 52% puts it at the same 55% of the visible circle the icon has.
function Add-SafeAreaWrapper([string] $xmlPath, [string] $targetPath, [double] $scale) {
    $content = Get-Content -LiteralPath $xmlPath -Raw
    $open = [regex]::Match($content, '(?s)^(.*?<vector[^>]*>\r?\n)')
    if (-not $open.Success) { throw "No <vector> root in $xmlPath" }
    $head = $open.Groups[1].Value
    $body = $content.Substring($head.Length)
    $body = [regex]::Replace($body, '</vector>\s*$', '')
    $offset = (512 - 512 * $scale) / 2
    $wrapped = $head +
    "    <group`n" +
    "        android:scaleX=`"$scale`"`n" +
    "        android:scaleY=`"$scale`"`n" +
    "        android:translateX=`"$offset`"`n" +
    "        android:translateY=`"$offset`">`n" +
    "    </group>`n</vector>`n"
    # The wrapper must SURROUND the body, so splice the body back in before the
    # closing tag.
    $wrapped = $wrapped -replace '(?s)\n    </group>\n</vector>', ("`n" + $body + "    </group>`n</vector>`n")
    Set-Content -LiteralPath $targetPath -Value $wrapped -Encoding utf8NoBOM
}

# The background keeps the export's own framing: the launcher mask does the
# cropping, which is what a full-bleed background is for.
Copy-Item -LiteralPath (Join-Path $work 'launcher-background.xml') -Destination (Join-Path $res 'ic_launcher_background.xml') -Force
Add-SafeAreaWrapper (Join-Path $work 'launcher-foreground.xml') (Join-Path $res 'ic_launcher_foreground.xml') 0.78
Add-SafeAreaWrapper (Join-Path $work 'launcher-silhouette.xml') (Join-Path $res 'ic_launcher_monochrome.xml') 0.78

# The splash shows the SAME composite the launcher shows — tile with the mark
# inset — because a splash that draws the ribbon alone reads as a mark bigger
# than the icon the user just tapped.
$splashForeground = Join-Path $work 'splash-foreground.xml'
Add-SafeAreaWrapper (Join-Path $work 'launcher-foreground.xml') $splashForeground 0.52

function Merge-Drawables([string] $backgroundPath, [string] $foregroundPath, [string] $targetPath) {
    $backgroundXml = Get-Content -LiteralPath $backgroundPath -Raw
    $foregroundXml = Get-Content -LiteralPath $foregroundPath -Raw
    $head = [regex]::Match($backgroundXml, '(?s)^(.*?<vector[^>]*>\r?\n)').Groups[1].Value
    $backgroundBody = [regex]::Replace($backgroundXml, '(?s)^.*?<vector[^>]*>\r?\n', '')
    $backgroundBody = [regex]::Replace($backgroundBody, '</vector>\s*$', '')
    $foregroundBody = [regex]::Replace($foregroundXml, '(?s)^.*?<vector[^>]*>\r?\n', '')
    $foregroundBody = [regex]::Replace($foregroundBody, '</vector>\s*$', '')
    Set-Content -LiteralPath $targetPath -Value ($head + $backgroundBody + $foregroundBody + "</vector>`n") -Encoding utf8NoBOM
}

Merge-Drawables (Join-Path $work 'launcher-background.xml') $splashForeground (Join-Path $res 'nexo_splash_mark.xml')

Write-Host 'Android brand drawables regenerated from' $BrandSvg
