# PowerShell setup script for Eclipse Memory Analyzer (MAT)
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir

$matDir = if ($env:MAT_HOME) { $env:MAT_HOME } else { Join-Path $rootDir "tools\mat\mat" }

if (Test-Path (Join-Path $matDir "MemoryAnalyzer.exe")) {
    Write-Host "Eclipse MAT is already installed at $matDir"
    exit 0
}

$parentDir = Split-Path -Parent $matDir
if (-not (Test-Path $parentDir)) {
    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
}

$version = "1.17.0.20260601"
$baseVersion = "1.17.0"
$archive = "MemoryAnalyzer-$version-win32.win32.x86_64.zip"
$destZip = Join-Path $parentDir $archive

$urls = @(
    "https://download.eclipse.org/mat/$baseVersion/rcp/$archive",
    "https://mirror.umd.edu/eclipse/mat/$baseVersion/rcp/$archive"
)

Write-Host "Downloading Eclipse MAT $version for Windows x86_64..."
$downloaded = $false
foreach ($url in $urls) {
    try {
        Write-Host "Trying: $url"
        Invoke-WebRequest -Uri $url -OutFile $destZip
        $downloaded = $true
        break
    } catch {
        Write-Warning "Failed downloading from $url"
    }
}

if (-not $downloaded) {
    Write-Error "Could not download Eclipse MAT from any mirrors."
    exit 1
}

Write-Host "Extracting $destZip to $parentDir..."
Expand-Archive -Path $destZip -DestinationPath $parentDir -Force
Remove-Item -Path $destZip -Force

Write-Host "Eclipse MAT successfully installed at $matDir"
