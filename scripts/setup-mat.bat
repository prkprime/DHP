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

for %%I in ("%MAT_DIR%\..") do set "PARENT_DIR=%%~fI"
if not exist "%PARENT_DIR%" mkdir "%PARENT_DIR%"

set "BASE_VERSION=1.17.0"
for /f "tokens=3 delims=<>" %%v in ('findstr /c:"<mat.version>" "%ROOT_DIR%\pom.xml" 2^>nul') do set "BASE_VERSION=%%v"
if not "%MAT_VERSION%"=="" set "BASE_VERSION=%MAT_VERSION%"
set "VERSION=%BASE_VERSION%.20260601"
set "ARCHIVE=MemoryAnalyzer-%VERSION%-win32.win32.x86_64.zip"
set "DEST_ZIP=%PARENT_DIR%\%ARCHIVE%"

echo Downloading Eclipse MAT %VERSION% for Windows x86_64...
curl -L --fail --output "%DEST_ZIP%" "https://ftp.fau.de/eclipse/mat/%BASE_VERSION%/rcp/%ARCHIVE%"
if errorlevel 1 curl -L --fail --output "%DEST_ZIP%" "https://download.eclipse.org/mat/%BASE_VERSION%/rcp/%ARCHIVE%"
if errorlevel 1 (
  echo Failed download from the first mirrors, trying fallback...
  curl -L --fail --output "%DEST_ZIP%" "https://mirror.umd.edu/eclipse/mat/%BASE_VERSION%/rcp/%ARCHIVE%"
)
if errorlevel 1 (
  echo Failed to download Eclipse MAT for Windows.
  exit /b 1
)

echo Extracting %DEST_ZIP% to %PARENT_DIR%...
powershell -Command "Expand-Archive -Path '%DEST_ZIP%' -DestinationPath '%PARENT_DIR%' -Force"
del /f /q "%DEST_ZIP%"
rem The archive unpacks into a top-level "mat" folder; honour a custom MAT_HOME / target directory name
if not exist "%MAT_DIR%" if exist "%PARENT_DIR%\mat" move "%PARENT_DIR%\mat" "%MAT_DIR%" >nul

echo Eclipse MAT successfully installed at %MAT_DIR%
