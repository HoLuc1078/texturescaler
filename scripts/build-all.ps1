# Build every version/loader module and collect the jars into dist/
# Usage:  powershell -File scripts/build-all.ps1 [-Only 1.20.1,1.21.1]
[CmdletBinding()]
param(
    [string[]] $Only
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# Only collect the jars of the current mod version, so leftovers from earlier
# builds in build/libs can never leak an obsolete jar into dist/.
$modVersion = ((Get-Content (Join-Path $root 'gradle\mod.properties') |
        Where-Object { $_ -match '^\s*mod_version\s*=' }) -replace '^\s*mod_version\s*=\s*', '').Trim()

# Every module declares, in its own gradle.properties, the JDK that runs its Gradle
# wrapper (`gradle_jdk`). 21 is the fallback for a module that omits it.
$modules = Get-ChildItem (Join-Path $root 'versions') -Directory | ForEach-Object {
    $mc = $_.Name
    Get-ChildItem $_.FullName -Directory | ForEach-Object {
        $rel = "versions/$mc/$($_.Name)"
        $jdk = 21
        $decl = (Get-Content (Join-Path $_.FullName 'gradle.properties') -ErrorAction SilentlyContinue) |
                Where-Object { $_ -match '^\s*gradle_jdk\s*=' }
        if ($decl) { $jdk = [int]($decl -replace '^\s*gradle_jdk\s*=\s*', '') }
        [pscustomobject]@{ Path = $rel; Jdk = $jdk }
    }
} | Where-Object { Test-Path (Join-Path $root "$($_.Path)\gradlew.bat") } | Sort-Object Path

$results = @()
foreach ($module in $modules) {
    $rel = $module.Path
    if ($Only -and -not ($Only | Where-Object { $rel -like "*/$_/*" })) { continue }

    $dir = Join-Path $root $rel
    $jdk = Join-Path $env:USERPROFILE ".jdks\temurin-$($module.Jdk)"
    if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
        $jdk = (Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
                Select-Object -First 1).FullName
    }

    Write-Host "=== building $rel (JDK $($module.Jdk)) ===" -ForegroundColor Cyan
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk\bin;$env:PATH"

    Push-Location $dir
    try {
        & cmd /c "gradlew.bat build --no-daemon --console=plain" 2>&1 | ForEach-Object { Write-Host $_ }
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $jars = @(Get-ChildItem (Join-Path $dir 'build\libs') -Filter "*-$modVersion.jar" -ErrorAction SilentlyContinue |
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
