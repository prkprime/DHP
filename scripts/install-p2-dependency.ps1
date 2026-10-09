# PowerShell script to install Eclipse p2 feature dependencies into Eclipse MAT
param(
    [Parameter(Position=0, Mandatory=$true)]
    [string]$Repository,
    [Parameter(Position=1, Mandatory=$true)]
    [string]$InstallIU,
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
$matDir = if ($env:MAT_HOME) { $env:MAT_HOME } else { Join-Path $rootDir "tools\mat\mat" }
$matBin = Join-Path $matDir "MemoryAnalyzer.exe"

if (-not (Test-Path $matBin)) {
    Write-Host "Eclipse MAT executable not found at $matBin. Running setup-mat.ps1..."
    & (Join-Path $scriptDir "setup-mat.ps1")
}

Write-Host "=== Installing p2 Dependency ==="
Write-Host "MAT Home:   $matDir"
Write-Host "Repository: $Repository"
Write-Host "Unit (IU):  $InstallIU"

$p2Args = @(
    "-consolelog",
    "-nosplash",
    "-application", "org.eclipse.equinox.p2.director",
    "-destination", $matDir,
    "-repository", $Repository,
    "-installIU", $InstallIU
)
if ($ExtraArgs) {
    $p2Args += $ExtraArgs
}

& $matBin $p2Args

Write-Host "=== p2 dependency installation finished successfully! ==="
