# PowerShell script to run Eclipse MAT in headless mode with ParseHeapDump
param(
    [Parameter(Position=0, Mandatory=$true)]
    [string]$DhpFile,
    [Parameter(Position=1, ValueFromRemainingArguments=$true)]
    [string[]]$Reports
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
$matDir = if ($env:MAT_HOME) { $env:MAT_HOME } else { Join-Path $rootDir "tools\mat\mat" }
$parseScript = Join-Path $matDir "ParseHeapDump.bat"

if (-not (Test-Path $parseScript)) {
    Write-Host "ParseHeapDump.bat not found at $parseScript. Running setup-mat.ps1..."
    & (Join-Path $scriptDir "setup-mat.ps1")
}

$bundlesInfo = Join-Path $matDir "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
if (Test-Path $bundlesInfo) {
    $hasDhp = Select-String -Path $bundlesInfo -Pattern "^org\.eclipse\.mat\.dhp," -Quiet
    if (-not $hasDhp) {
        Write-Host "DHP plugin not detected in bundles.info. Running install-mat-plugin.ps1..."
        & (Join-Path $scriptDir "install-mat-plugin.ps1")
    }
}

$resolvedDhp = (Resolve-Path $DhpFile).Path
if (-not (Test-Path $resolvedDhp)) {
    Write-Error "Error: DHP file does not exist: $DhpFile"
    exit 1
}

if (-not $Reports -or $Reports.Length -eq 0) {
    $Reports = @("org.eclipse.mat.api:suspects")
}

Write-Host "=== Running Eclipse MAT Headless Parse ==="
Write-Host "DHP Descriptor: $resolvedDhp"
Write-Host "Reports:        $($Reports -join ' ')"
Write-Host "MAT Home:       $matDir"

& $parseScript $resolvedDhp $Reports

Write-Host "=== Headless analysis complete! ==="
