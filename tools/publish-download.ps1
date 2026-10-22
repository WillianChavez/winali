param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z][a-z0-9-]*$')]
    [string]$App,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,
    [string]$DownloadsDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '..\winali-downloads'),
    [switch]$Push
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$artifactDirectory = if ($App -eq 'winali') {
    Join-Path $projectRoot ".local\releases\$App\$Version"
} else {
    Join-Path $projectRoot "apps\$App\.local\releases\$App\$Version"
}
$apk = Join-Path $artifactDirectory "$App.apk"
$sourceManifest = Join-Path $artifactDirectory 'release.json'
$downloadsDirectory = (Resolve-Path -LiteralPath $DownloadsDirectory).Path
$downloadsRepo = (git -C $downloadsDirectory rev-parse --show-toplevel).Trim()
$versionDirectory = Join-Path $downloadsRepo "apps\$App\$Version"
$latestDirectory = Join-Path $downloadsRepo "apps\$App\latest"

if (!(Test-Path -LiteralPath $apk) -or !(Test-Path -LiteralPath $sourceManifest)) { throw "Missing release artifacts in $artifactDirectory" }
$manifest = Get-Content -LiteralPath $sourceManifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.release.versionName -ne $Version) { throw 'The release manifest version does not match -Version.' }
$manifest.release.url = "https://willianchavez.github.io/winali-downloads/apps/$App/$Version/$App.apk"
$manifestText = $manifest | ConvertTo-Json -Depth 20

foreach ($directory in @($versionDirectory, $latestDirectory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
Copy-Item -LiteralPath $apk -Destination (Join-Path $versionDirectory "$App.apk") -Force
Copy-Item -LiteralPath $apk -Destination (Join-Path $latestDirectory "$App.apk") -Force
$manifestText | Set-Content -LiteralPath (Join-Path $versionDirectory 'release.json') -Encoding utf8
$manifestText | Set-Content -LiteralPath (Join-Path $latestDirectory 'release.json') -Encoding utf8

Push-Location $projectRoot
try {
    & node tools/catalog.mjs sync $App
    if ($LASTEXITCODE -ne 0) { throw 'Catalog synchronization failed.' }
    Copy-Item -LiteralPath (Join-Path $projectRoot 'catalog\index.json') -Destination (Join-Path $downloadsRepo 'catalog.json') -Force
} finally { Pop-Location }

git -C $downloadsRepo add --all
git -C $downloadsRepo commit -m "Publish $App $Version binary"
if ($Push) { git -C $downloadsRepo push }
Write-Output "Prepared $App $Version in $downloadsRepo. Catalog and embedded catalog were updated locally; root changes still need review and commit."
