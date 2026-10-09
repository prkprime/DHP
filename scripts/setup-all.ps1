# PowerShell script for Complete Environment Setup
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir

Write-Host "================================================================================"
Write-Host "Dynamic Heap Parser (DHP) - Complete Environment Setup"
Write-Host "================================================================================"

Write-Host ""
Write-Host "[Step 1/4] Installing Eclipse MAT local dependencies into Maven..."
& (Join-Path $scriptDir "setup-deps.ps1")

Write-Host ""
Write-Host "[Step 2/4] Building full DHP reactor modules..."
Push-Location $rootDir
mvn clean install -DskipTests
Pop-Location

Write-Host ""
Write-Host "[Step 3/4] Setting up Eclipse MAT standalone tooling..."
& (Join-Path $scriptDir "setup-mat.ps1")

Write-Host ""
Write-Host "[Step 4/4] Installing DHP plugin into Eclipse MAT..."
& (Join-Path $scriptDir "install-mat-plugin.ps1")

Write-Host ""
Write-Host "================================================================================"
Write-Host "DHP setup complete! You are ready to parse heap dumps and run Eclipse MAT."
Write-Host "================================================================================"
