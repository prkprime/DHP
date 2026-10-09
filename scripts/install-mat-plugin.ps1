# PowerShell script to deploy DHP shaded plugin to Eclipse MAT
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
$matDir = if ($env:MAT_HOME) { $env:MAT_HOME } else { Join-Path $rootDir "tools\mat\mat" }

if (-not (Test-Path $matDir)) {
    Write-Host "MAT directory not found at $matDir. Running setup-mat.ps1..."
    & (Join-Path $scriptDir "setup-mat.ps1")
}

$sourceJar = Join-Path $rootDir "dhp-mat-plugin\target\dhp-mat-plugin-1.0.0-SNAPSHOT.jar"
if (-not (Test-Path $sourceJar)) {
    Write-Host "Plugin JAR not found. Building dhp-mat-plugin..."
    Push-Location $rootDir
    mvn package -pl dhp-mat-plugin -am -DskipTests
    Pop-Location
}

$targetJarName = "org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar"
$targetJar = Join-Path $matDir "plugins\$targetJarName"
$bundlesInfo = Join-Path $matDir "configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"

Write-Host "Deploying DHP plugin to $targetJar..."
Copy-Item -Path $sourceJar -Destination $targetJar -Force

Write-Host "Updating Equinox bundles.info configuration..."
$bundleLine = "org.eclipse.mat.dhp,1.0.0.SNAPSHOT,plugins/$targetJarName,4,true"
if (Test-Path $bundlesInfo) {
    $lines = Get-Content $bundlesInfo | Where-Object { -not $_.StartsWith("org.eclipse.mat.dhp,") }
    $lines += $bundleLine
    Set-Content -Path $bundlesInfo -Value $lines
} else {
    Set-Content -Path $bundlesInfo -Value $bundleLine
}

Write-Host "Purging OSGi bundle cache..."
$osgiCache = Join-Path $matDir "configuration\org.eclipse.osgi"
if (Test-Path $osgiCache) {
    Remove-Item -Path $osgiCache -Recurse -Force
}

Write-Host "=== Dynamic Heap Parser plugin successfully installed into Eclipse MAT! ==="
