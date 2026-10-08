@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

echo ================================================================================
echo Dynamic Heap Parser (DHP) - Complete Environment Setup
echo ================================================================================

echo.
echo [Step 1/4] Installing Eclipse MAT local dependencies into Maven...
call "%SCRIPT_DIR%setup-deps.bat"
if errorlevel 1 exit /b 1

echo.
echo [Step 2/4] Building full DHP reactor modules...
cd /d "%ROOT_DIR%"
call mvn clean install -DskipTests
if errorlevel 1 exit /b 1

echo.
echo [Step 3/4] Setting up Eclipse MAT standalone tooling...
call "%SCRIPT_DIR%setup-mat.bat"
if errorlevel 1 exit /b 1

echo.
echo [Step 4/4] Installing DHP plugin into Eclipse MAT...
call "%SCRIPT_DIR%install-mat-plugin.bat"
if errorlevel 1 exit /b 1

echo.
echo ================================================================================
echo DHP setup complete! You are ready to parse heap dumps and run Eclipse MAT.
echo ================================================================================
