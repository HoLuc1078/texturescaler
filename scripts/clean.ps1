# Remove every generated build artefact and cache (all of it is regenerable).
# The built mod jars in dist/ are KEPT unless -IncludeDist is passed.
# Usage: powershell -NoProfile -File scripts/clean.ps1 [-IncludeDist]
param([switch] $IncludeDist)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$names = @('build', '.gradle', 'run', 'run-data', 'mcmodsrepo')
$targets = Get-ChildItem $root -Recurse -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { $names -contains $_.Name -and $_.FullName -notlike '*\.git\*' }
foreach ($t in $targets) {
    Remove-Item -Recurse -Force $t.FullName -ErrorAction SilentlyContinue
    Write-Host ('removed ' + $t.FullName.Substring($root.Length + 1))
}
Get-ChildItem $root -Recurse -Filter '*.log' -Force -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
if ($IncludeDist -and (Test-Path (Join-Path $root 'dist'))) {
    Remove-Item -Recurse -Force (Join-Path $root 'dist')
    Write-Host 'removed dist/'
}
Write-Host 'clean done'