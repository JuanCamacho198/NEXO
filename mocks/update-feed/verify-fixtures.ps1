$ErrorActionPreference = 'Stop'
$fail = $false
function Check($cond, $msg) {
  if (-not $cond) { Write-Output "FAIL: $msg"; $script:fail = $true }
}

$a = Get-Content 'mocks/update-feed/android-latest.json' -Raw | ConvertFrom-Json
$d = Get-Content 'mocks/update-feed/desktop-latest.json' -Raw | ConvertFrom-Json

Check ($a.version -eq '0.3.2') 'android version must be 0.3.2'
Check ($a.versionCode -gt 300) 'android versionCode must be above 300'
Check ($a.channel -eq 'stable') 'android channel must be stable'
Check ($null -ne $a.notes -and $a.notes.Length -gt 0) 'android notes required'
Check ($null -ne $a.pubDate) 'android pubDate required'
Check ($a.assets.Count -ge 1) 'android needs at least one asset'
foreach ($asset in $a.assets) {
  Check ($asset.url -match 'nexo-android-v0\.3\.2\.apk$') "android asset naming: $($asset.url)"
  Check ($null -ne $asset.abi) 'android asset abi required'
  Check ($asset.size -gt 0) 'android asset size required'
}

Check ($d.version -eq '0.3.2') 'desktop version must be 0.3.2'
Check ($d.channel -eq 'stable') 'desktop channel must be stable'
Check ($null -ne $d.notes -and $d.notes.Length -gt 0) 'desktop notes required'
Check ($null -ne $d.pub_date) 'desktop pub_date required'
$names = @($d.platforms.PSObject.Properties.Name)
Check ($names.Count -ge 1) 'desktop needs at least one platform'
# Real published asset names derive from productName (`Nexo Desktop`) and the
# version; GitHub replaces the space in the local bundle name with a dot, so
# the released names read `Nexo.Desktop_<version>_<target>.<ext>`.
$expectedDesktopAsset = @{
  'linux-x86_64'    = 'Nexo\.Desktop_0\.3\.2_amd64\.AppImage$'
  'macos-universal' = 'Nexo\.Desktop_0\.3\.2_universal\.dmg$'
  'windows-x86_64'  = 'Nexo\.Desktop_0\.3\.2_x64-setup\.exe$'
}
foreach ($k in $names) {
  $e = $d.platforms.$k
  Check ($null -ne $e.url) "desktop platform $k url required"
  Check ($null -ne $e.signature) "desktop platform $k signature required"
  Check ($expectedDesktopAsset.ContainsKey($k)) "desktop platform $k is a known target"
  if ($expectedDesktopAsset.ContainsKey($k)) {
    Check ($e.url -match $expectedDesktopAsset[$k]) "desktop url naming for $k : $($e.url)"
  }
}

if (-not $fail) { Write-Output 'ALL FIXTURE CHECKS PASS' } else { exit 1 }
