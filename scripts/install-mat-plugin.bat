@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "MAT_DIR=%ROOT_DIR%\tools\mat\mat"
if not "%MAT_HOME%"=="" set "MAT_DIR=%MAT_HOME%"

if not exist "%MAT_DIR%" (
  echo MAT directory not found at %MAT_DIR%. Running setup-mat.bat...
  call "%SCRIPT_DIR%setup-mat.bat"
  if errorlevel 1 exit /b 1
)

set "SOURCE_JAR=%ROOT_DIR%\dhp-mat-plugin\target\dhp-mat-plugin-1.0.0-SNAPSHOT.jar"

if not exist "%SOURCE_JAR%" (
  echo Plugin JAR not found. Building dhp-mat-plugin...
  cd /d "%ROOT_DIR%"
  call mvn package -pl dhp-mat-plugin -am -DskipTests
  if errorlevel 1 exit /b 1
)

set "TARGET_JAR_NAME=org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar"
set "TARGET_JAR=%MAT_DIR%\plugins\%TARGET_JAR_NAME%"
set "BUNDLES_INFO=%MAT_DIR%\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"

echo Deploying DHP plugin to %TARGET_JAR%...
copy /y "%SOURCE_JAR%" "%TARGET_JAR%" >nul

echo Updating Equinox bundles.info configuration...
set "TEMP_INFO=%TEMP%\bundles_%RANDOM%.info"
if exist "%BUNDLES_INFO%" (
  findstr /v /b /c:"org.eclipse.mat.dhp," "%BUNDLES_INFO%" > "%TEMP_INFO%" 2>nul
)
echo org.eclipse.mat.dhp,1.0.0.SNAPSHOT,plugins/%TARGET_JAR_NAME%,4,true>> "%TEMP_INFO%"
move /y "%TEMP_INFO%" "%BUNDLES_INFO%" >nul

echo Purging OSGi bundle cache...
if exist "%MAT_DIR%\configuration\org.eclipse.osgi" rd /s /q "%MAT_DIR%\configuration\org.eclipse.osgi"

echo === Dynamic Heap Parser plugin successfully installed into Eclipse MAT! ===
