@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "MAT_DIR=%ROOT_DIR%\tools\mat\mat"
if not "%MAT_HOME%"=="" set "MAT_DIR=%MAT_HOME%"

if "%~1"=="" (
  echo Usage: scripts\install-p2-dependency.bat ^<repository-url^> ^<iu-id^> [extra-p2-args...]
  echo Example:
  echo   scripts\install-p2-dependency.bat https://download.eclipse.org/mat/1.17.0/update-site/ org.eclipse.mat.chart.feature.feature.group
  exit /b 1
)

set "REPO=%~1"
set "IU=%~2"
shift
shift

set "MAT_BIN=%MAT_DIR%\MemoryAnalyzer.exe"

if not exist "%MAT_BIN%" (
  echo Eclipse MAT executable not found at %MAT_BIN%. Running setup-mat.bat first...
  call "%SCRIPT_DIR%setup-mat.bat"
  if errorlevel 1 exit /b 1
)

echo === Installing p2 Dependency ===
echo MAT Home:   %MAT_DIR%
echo Repository: %REPO%
echo Unit (IU):  %IU%

"%MAT_BIN%" ^
  -consolelog ^
  -nosplash ^
  -application org.eclipse.equinox.p2.director ^
  -destination "%MAT_DIR%" ^
  -repository "%REPO%" ^
  -installIU "%IU%" %*

if errorlevel 1 (
  echo Error: p2 installation failed.
  exit /b %errorlevel%
)

echo === p2 dependency installation finished successfully! ===
