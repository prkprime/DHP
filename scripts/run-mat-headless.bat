@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "MAT_DIR=%ROOT_DIR%\tools\mat\mat"
if not "%MAT_HOME%"=="" set "MAT_DIR=%MAT_HOME%"

if "%~1"=="" (
  echo Usage: scripts\run-mat-headless.bat ^<dump.dhp^> [report-ids...]
  echo Example:
  echo   scripts\run-mat-headless.bat dump.dhp org.eclipse.mat.api:suspects
  exit /b 1
)

set "DHP_FILE=%~f1"
shift

set "PARSE_SCRIPT=%MAT_DIR%\ParseHeapDump.bat"

if not exist "%PARSE_SCRIPT%" (
  echo ParseHeapDump.bat not found at %PARSE_SCRIPT%. Running setup-mat.bat...
  call "%SCRIPT_DIR%setup-mat.bat"
  if errorlevel 1 exit /b 1
)

set "BUNDLES_INFO=%MAT_DIR%\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
if exist "%BUNDLES_INFO%" (
  findstr /b /c:"org.eclipse.mat.dhp," "%BUNDLES_INFO%" >nul 2>&1
  if errorlevel 1 (
    echo DHP plugin not detected in bundles.info. Running install-mat-plugin.bat...
    call "%SCRIPT_DIR%install-mat-plugin.bat"
  )
)

if not exist "%DHP_FILE%" (
  echo Error: DHP file does not exist: %DHP_FILE%
  exit /b 1
)

set "REPORTS=%~1"
if "%REPORTS%"=="" set "REPORTS=org.eclipse.mat.api:suspects"

echo === Running Eclipse MAT Headless Parse ===
echo DHP Descriptor: %DHP_FILE%
echo Reports:        %REPORTS%
echo MAT Home:       %MAT_DIR%

call "%PARSE_SCRIPT%" "%DHP_FILE%" %REPORTS%

echo === Headless analysis complete! ===
