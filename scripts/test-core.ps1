# Compile and run the shared-core self-test (no Minecraft, no Gradle test framework needed).
# Usage: pwsh -File scripts/test-core.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$jdk = Join-Path $env:USERPROFILE '.jdks\temurin-17'
if (-not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    $jdk = (Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } | Select-Object -First 1).FullName
}
$gsonDir = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson'
$gson = Get-ChildItem $gsonDir -Recurse -Filter 'gson-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $gson) { throw 'No cached Gson jar found; run any module build once first.' }

$out = Join-Path $env:TEMP 'texturescaler-core-selftest'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$src = Get-ChildItem (Join-Path $root 'core\src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 --release 8 -cp $gson.FullName -d $out $src
if ($LASTEXITCODE -ne 0) { throw 'core self-test did not compile' }
& (Join-Path $jdk 'bin\java.exe') -cp "$out;$($gson.FullName)" com.evernight.texturescaler.core.CoreSelfTest
exit $LASTEXITCODE