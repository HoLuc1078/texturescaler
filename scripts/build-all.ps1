# Build every version/loader module and collect the jars into dist/
# Usage:  pwsh -File scripts/build-all.ps1 [-Only 1.20.1,1.21.1] [-SkipLegacy]
[CmdletBinding()]
param(
    [string[]] $Only,
    [switch]   $SkipLegacy
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# module path -> JDK major version used to *run* Gradle
$modules = [ordered]@{
    'versions/1.12.2/forge'  = 17
    'versions/1.16.5/forge'  = 17
    'versions/1.16.5/fabric' = 21
    'versions/1.18.2/forge'  = 17
    'versions/1.18.2/fabric' = 21
    'versions/1.19.2/forge'  = 17
    'versions/1.19.2/fabric' = 21
    'versions/1.20.1/forge'  = 17
    'versions/1.20.1/fabric' = 21
    'versions/1.21.1/forge'  = 21
    'versions/1.21.1/fabric' = 21
    'versions/1.21.1/neoforge' = 21
    'versions/1.21.4/fabric' = 21
    'versions/1.21.4/neoforge' = 21
}

$results = @()
foreach ($rel in $modules.Keys) {
    if ($Only -and -not ($Only | Where-Object { $rel -like "*/$_/*" -or $rel -like "*/$_" })) { continue }
    if ($SkipLegacy -and $rel -like '*1.12.2*') { continue }

    $dir = Join-Path $root $rel
    if (-not (Test-Path $dir)) { Write-Host "skip (missing): $rel" -ForegroundColor DarkGray; continue }

    $jdkMajor = $modules[$rel]
    $jdk = Join-Path $env:USERPROFILE ".jdks\temurin-$jdkMajor"
    if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
        $jdk = (Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
                Select-Object -First 1).FullName
    }

    Write-Host "=== building $rel (JDK $jdkMajor) ===" -ForegroundColor Cyan
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk\bin;$env:PATH"

    Push-Location $dir
    try {
        & cmd /c "gradlew.bat build --no-daemon --console=plain" 2>&1 | ForEach-Object { Write-Host $_ }
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $jars = @(Get-ChildItem (Join-Path $dir 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -notmatch 'sources|javadoc|dev' })
    if ($code -eq 0 -and $jars.Count -gt 0) {
        foreach ($j in $jars) { Copy-Item $j.FullName (Join-Path $dist $j.Name) -Force }
        $results += [pscustomobject]@{ Module = $rel; Status = 'OK'; Jars = ($jars.Name -join ', ') }
    } else {
        $results += [pscustomobject]@{ Module = $rel; Status = "FAIL($code)"; Jars = '' }
    }
}

Write-Host ""
Write-Host "=== summary ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize
