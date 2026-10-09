@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "MAT_DIR=%ROOT_DIR%\tools\mat\mat"
if not "%MAT_HOME%"=="" set "MAT_DIR=%MAT_HOME%"

if exist "%MAT_DIR%\MemoryAnalyzer.exe" (
  echo Eclipse MAT is already installed at %MAT_DIR%
  exit /b 0
)

set "PARENT_DIR=%ROOT_DIR%\tools\mat"
if not exist "%PARENT_DIR%" mkdir "%PARENT_DIR%"

set "BASE_VERSION=1.17.0"
if not "%MAT_VERSION%"=="" set "BASE_VERSION=%MAT_VERSION%"
set "VERSION=%BASE_VERSION%.20260601"
set "ARCHIVE=MemoryAnalyzer-%VERSION%-win32.win32.x86_64.zip"
set "DEST_ZIP=%PARENT_DIR%\%ARCHIVE%"

echo Downloading Eclipse MAT %VERSION% for Windows x86_64...
curl -L --fail --output "%DEST_ZIP%" "https://download.eclipse.org/mat/%BASE_VERSION%/rcp/%ARCHIVE%"
if errorlevel 1 (
  echo Failed download from primary mirror, trying fallback...
  curl -L --fail --output "%DEST_ZIP%" "https://mirror.umd.edu/eclipse/mat/%BASE_VERSION%/rcp/%ARCHIVE%"
)
if errorlevel 1 (
  echo Failed to download Eclipse MAT for Windows.
  exit /b 1
)

echo Extracting %DEST_ZIP% to %PARENT_DIR%...
powershell -Command "Expand-Archive -Path '%DEST_ZIP%' -DestinationPath '%PARENT_DIR%' -Force"
del /f /q "%DEST_ZIP%"

echo Eclipse MAT successfully installed at %MAT_DIR%
