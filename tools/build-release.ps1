param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedCertificateSha256,
    [string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'Winali/signing'),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$remote = (& git -C $projectRoot remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $remote -notmatch 'github\.com[:/](?<owner>[^/]+)/(?<app>[a-z][a-z0-9-]*)(?:\.git)?$') {
    throw 'A GitHub origin remote is required.'
}
$owner = $Matches.owner
$app = $Matches.app
$parts = $Version.Split('.') | ForEach-Object { [long]$_ }
$code = $parts[0] * 1000000 + $parts[1] * 1000 + $parts[2]
if ($parts[1] -gt 999 -or $parts[2] -gt 999 -or $code -lt 1 -or $code -gt [int]::MaxValue) { throw 'Invalid Android version code.' }
$keyPath = Join-Path $SigningDirectory "$app.jks"
$credentialsPath = Join-Path $SigningDirectory "$app.json"
if (!(Test-Path -LiteralPath $keyPath) -or !(Test-Path -LiteralPath $credentialsPath)) { throw "Missing private signing files for $app in $SigningDirectory" }
$credentials = Get-Content -LiteralPath $credentialsPath -Raw | ConvertFrom-Json
if (!$credentials.storePassword -or !$credentials.keyPassword -or !$credentials.alias) { throw 'Incomplete signing credentials.' }
if (!$JavaHome) { $JavaHome = Join-Path $env:LOCALAPPDATA 'Programs/REA/java/jdk-21.0.12.1+1' }
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 21 installation.' }
$output = Join-Path $env:LOCALAPPDATA "Winali/releases/$app/$Version"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$variables = @('JAVA_HOME','ANDROID_HOME','VERSION_NAME','VERSION_CODE','SIGNING_KEYSTORE','SIGNING_STORE_PASSWORD','SIGNING_KEY_PASSWORD','SIGNING_KEY_ALIAS')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $env:VERSION_NAME = $Version
    $env:VERSION_CODE = "$code"
    $env:SIGNING_KEYSTORE = (Resolve-Path -LiteralPath $keyPath).Path
    $env:SIGNING_STORE_PASSWORD = $credentials.storePassword
    $env:SIGNING_KEY_PASSWORD = $credentials.keyPassword
    $env:SIGNING_KEY_ALIAS = $credentials.alias
    & ./gradlew.bat :app:assembleRelease :app:lintDebug :app:testDebugUnitTest --console=plain *> (Join-Path $output 'build.log')
    if ($LASTEXITCODE -ne 0) { throw "Build failed. See $output/build.log" }
    $apkPath = Join-Path $output "$app.apk"
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apkPath
    & node tools/release-manifest.mjs $apkPath "https://github.com/$owner/$app" "v$Version" CHANGELOG.md
    if ($LASTEXITCODE -ne 0) { throw 'APK signature or manifest verification failed.' }
    $manifest = Get-Content -LiteralPath (Join-Path $output 'release.json') -Raw | ConvertFrom-Json
    if ($manifest.release.versionCode -ne $code -or $manifest.release.versionName -ne $Version) { throw 'The built APK version does not match the request.' }
    if ($ExpectedCertificateSha256 -and $manifest.release.certificateSha256 -ne $ExpectedCertificateSha256.ToLowerInvariant()) {
        throw 'Signing certificate changed. Do not publish this APK.'
    }
    Write-Output "Verified signed APK and release.json: $output"
} finally {
    Pop-Location
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
