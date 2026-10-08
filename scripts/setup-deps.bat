@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "LIB_DIR=%ROOT_DIR%\lib"

echo === Installing Local Eclipse MAT Dependencies into Maven Local Repository ===

call mvn install:install-file ^
  -Dfile="%LIB_DIR%\org.eclipse.mat.api_1.17.0.202606011933.jar" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.api ^
  -Dversion=1.17.0 ^
  -Dpackaging=jar
if errorlevel 1 exit /b %errorlevel%

call mvn install:install-file ^
  -Dfile="%LIB_DIR%\org.eclipse.mat.parser_1.17.0.202606011933.jar" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.parser ^
  -Dversion=1.17.0 ^
  -Dpackaging=jar
if errorlevel 1 exit /b %errorlevel%

call mvn install:install-file ^
  -Dfile="%LIB_DIR%\org.eclipse.mat.hprof_1.17.0.202606011933.jar" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.hprof ^
  -Dversion=1.17.0 ^
  -Dpackaging=jar
if errorlevel 1 exit /b %errorlevel%

call mvn install:install-file ^
  -Dfile="%LIB_DIR%\org.eclipse.mat.report_1.17.0.202606011933.jar" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.report ^
  -Dversion=1.17.0 ^
  -Dpackaging=jar
if errorlevel 1 exit /b %errorlevel%

echo === All Eclipse MAT JARs installed successfully! ===
