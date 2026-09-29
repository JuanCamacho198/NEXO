$ErrorActionPreference = 'Stop'
$fail = $false
function Check($cond, $msg) {
  if (-not $cond) { Write-Output "FAIL: $msg"; $script:fail = $true }
}

$a = Get-Content 'mocks/update-feed/android-latest.json' -Raw | ConvertFrom-Json
$d = Get-Content 'mocks/update-feed/desktop-latest.json' -Raw | ConvertFrom-Json

Check ($a.version -eq '0.4.0') 'android version must be 0.4.0'
Check ($a.versionCode -gt 300) 'android versionCode must be above 300'
Check ($a.channel -eq 'stable') 'android channel must be stable'
Check ($null -ne $a.notes -and $a.notes.Length -gt 0) 'android notes required'
Check ($null -ne $a.pubDate) 'android pubDate required'
Check ($a.assets.Count -ge 1) 'android needs at least one asset'
foreach ($asset in $a.assets) {
  Check ($asset.url -match 'nextpage-android-v0\.4\.0\.apk$') "android asset naming: $($asset.url)"
  Check ($null -ne $asset.abi) 'android asset abi required'
  Check ($asset.size -gt 0) 'android asset size required'
}

Check ($d.version -eq '0.4.0') 'desktop version must be 0.4.0'
Check ($d.channel -eq 'stable') 'desktop channel must be stable'
Check ($null -ne $d.notes -and $d.notes.Length -gt 0) 'desktop notes required'
Check ($null -ne $d.pub_date) 'desktop pub_date required'
$names = @($d.platforms.PSObject.Properties.Name)
Check ($names.Count -ge 1) 'desktop needs at least one platform'
foreach ($k in $names) {
  $e = $d.platforms.$k
  Check ($null -ne $e.url) "desktop platform $k url required"
  Check ($null -ne $e.signature) "desktop platform $k signature required"
  Check ($e.url -match [regex]::Escape("nextpage-desktop-v0.4.0-$k")) "desktop url naming for $k : $($e.url)"
}

if (-not $fail) { Write-Output 'ALL FIXTURE CHECKS PASS' } else { exit 1 }
