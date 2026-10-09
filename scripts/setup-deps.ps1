# PowerShell script to resolve and install Eclipse MAT dependencies into local Maven cache
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
$matDir = if ($env:MAT_HOME) { $env:MAT_HOME } else { Join-Path $rootDir "tools\mat\mat" }
$pluginsDir = Join-Path $matDir "plugins"
$libDir = Join-Path $rootDir "lib"
$matVersion = "1.17.0"

Write-Host "=== Dynamic Heap Parser: Resolving Eclipse MAT $matVersion Dependencies ==="

$m2Mat = Join-Path $HOME ".m2\repository\org\eclipse\mat"
if ((Test-Path (Join-Path $m2Mat "org.eclipse.mat.api\$matVersion\org.eclipse.mat.api-$matVersion.jar")) -and
    (Test-Path (Join-Path $m2Mat "org.eclipse.mat.parser\$matVersion\org.eclipse.mat.parser-$matVersion.jar")) -and
    (Test-Path (Join-Path $m2Mat "org.eclipse.mat.hprof\$matVersion\org.eclipse.mat.hprof-$matVersion.jar")) -and
    (Test-Path (Join-Path $m2Mat "org.eclipse.mat.report\$matVersion\org.eclipse.mat.report-$matVersion.jar"))) {
    Write-Host "Eclipse MAT $matVersion dependencies are already installed in local Maven repository."
    exit 0
}

function Find-Jar($artifact) {
    if (Test-Path $pluginsDir) {
        $found = Get-ChildItem -Path $pluginsDir -Filter "org.eclipse.mat.${artifact}_${matVersion}*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    if (Test-Path $libDir) {
        $found = Get-ChildItem -Path $libDir -Filter "org.eclipse.mat.${artifact}_${matVersion}*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

$apiJar = Find-Jar "api"
$parserJar = Find-Jar "parser"
$hprofJar = Find-Jar "hprof"
$reportJar = Find-Jar "report"

if (-not $apiJar -or -not $parserJar -or -not $hprofJar -or -not $reportJar) {
    Write-Host "Eclipse MAT JARs not found locally. Running setup-mat.ps1..."
    & (Join-Path $scriptDir "setup-mat.ps1")
    $apiJar = Find-Jar "api"
    $parserJar = Find-Jar "parser"
    $hprofJar = Find-Jar "hprof"
    $reportJar = Find-Jar "report"
}

if (-not $apiJar -or -not $parserJar -or -not $hprofJar -or -not $reportJar) {
    Write-Error "Could not locate Eclipse MAT dependencies for version $matVersion."
    exit 1
}

Write-Host "Installing org.eclipse.mat.api ($apiJar)..."
mvn -B install:install-file -Dfile="$apiJar" -DgroupId="org.eclipse.mat" -DartifactId="org.eclipse.mat.api" -Dversion="$matVersion" -Dpackaging="jar" | Out-Null

Write-Host "Installing org.eclipse.mat.parser ($parserJar)..."
mvn -B install:install-file -Dfile="$parserJar" -DgroupId="org.eclipse.mat" -DartifactId="org.eclipse.mat.parser" -Dversion="$matVersion" -Dpackaging="jar" | Out-Null

Write-Host "Installing org.eclipse.mat.hprof ($hprofJar)..."
mvn -B install:install-file -Dfile="$hprofJar" -DgroupId="org.eclipse.mat" -DartifactId="org.eclipse.mat.hprof" -Dversion="$matVersion" -Dpackaging="jar" | Out-Null

Write-Host "Installing org.eclipse.mat.report ($reportJar)..."
mvn -B install:install-file -Dfile="$reportJar" -DgroupId="org.eclipse.mat" -DartifactId="org.eclipse.mat.report" -Dversion="$matVersion" -Dpackaging="jar" | Out-Null

Write-Host "=== All Eclipse MAT $matVersion dependencies installed successfully! ==="
