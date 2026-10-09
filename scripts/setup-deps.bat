@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "MAT_DIR=%ROOT_DIR%\tools\mat\mat"
if not "%MAT_HOME%"=="" set "MAT_DIR=%MAT_HOME%"
set "PLUGINS_DIR=%MAT_DIR%\plugins"
set "LIB_DIR=%ROOT_DIR%\lib"
set "MAT_VERSION=1.17.0"

echo === Dynamic Heap Parser: Resolving Eclipse MAT %MAT_VERSION% Dependencies ===

set "M2_MAT=%USERPROFILE%\.m2\repository\org\eclipse\mat"
if exist "%M2_MAT%\org.eclipse.mat.api\%MAT_VERSION%\org.eclipse.mat.api-%MAT_VERSION%.jar" (
  if exist "%M2_MAT%\org.eclipse.mat.parser\%MAT_VERSION%\org.eclipse.mat.parser-%MAT_VERSION%.jar" (
    if exist "%M2_MAT%\org.eclipse.mat.hprof\%MAT_VERSION%\org.eclipse.mat.hprof-%MAT_VERSION%.jar" (
      if exist "%M2_MAT%\org.eclipse.mat.report\%MAT_VERSION%\org.eclipse.mat.report-%MAT_VERSION%.jar" (
        echo Eclipse MAT %MAT_VERSION% dependencies are already installed in local Maven repository.
        exit /b 0
      )
    )
  )
)

set "API_JAR="
set "PARSER_JAR="
set "HPROF_JAR="
set "REPORT_JAR="

rem Check in plugins folder
if exist "%PLUGINS_DIR%" (
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.api_%MAT_VERSION%*.jar") do if exist "%%~ff" set "API_JAR=%%~ff"
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.parser_%MAT_VERSION%*.jar") do if exist "%%~ff" set "PARSER_JAR=%%~ff"
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.hprof_%MAT_VERSION%*.jar") do if exist "%%~ff" set "HPROF_JAR=%%~ff"
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.report_%MAT_VERSION%*.jar") do if exist "%%~ff" set "REPORT_JAR=%%~ff"
)

rem Fallback check in lib folder
if "%API_JAR%"=="" if exist "%LIB_DIR%" (
  for %%f in ("%LIB_DIR%\org.eclipse.mat.api_%MAT_VERSION%*.jar") do if exist "%%~ff" set "API_JAR=%%~ff"
  for %%f in ("%LIB_DIR%\org.eclipse.mat.parser_%MAT_VERSION%*.jar") do if exist "%%~ff" set "PARSER_JAR=%%~ff"
  for %%f in ("%LIB_DIR%\org.eclipse.mat.hprof_%MAT_VERSION%*.jar") do if exist "%%~ff" set "HPROF_JAR=%%~ff"
  for %%f in ("%LIB_DIR%\org.eclipse.mat.report_%MAT_VERSION%*.jar") do if exist "%%~ff" set "REPORT_JAR=%%~ff"
)

if "%API_JAR%"=="" (
  echo Eclipse MAT JARs not found locally. Running setup-mat.bat...
  call "%SCRIPT_DIR%setup-mat.bat"
  if errorlevel 1 exit /b 1

  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.api_%MAT_VERSION%*.jar") do if exist "%%~ff" set "API_JAR=%%~ff"
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.parser_%MAT_VERSION%*.jar") do if exist "%%~ff" set "PARSER_JAR=%%~ff"
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.hprof_%MAT_VERSION%*.jar") do if exist "%%~ff" set "HPROF_JAR=%%~ff"
  for %%f in ("%PLUGINS_DIR%\org.eclipse.mat.report_%MAT_VERSION%*.jar") do if exist "%%~ff" set "REPORT_JAR=%%~ff"
)

if "%API_JAR%"=="" (
  echo Error: Could not locate Eclipse MAT dependencies for version %MAT_VERSION%.
  exit /b 1
)

echo Installing org.eclipse.mat.api (%API_JAR%)...
call mvn -B install:install-file ^
  -Dfile="%API_JAR%" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.api ^
  -Dversion=%MAT_VERSION% ^
  -Dpackaging=jar >nul
if errorlevel 1 exit /b %errorlevel%

echo Installing org.eclipse.mat.parser (%PARSER_JAR%)...
call mvn -B install:install-file ^
  -Dfile="%PARSER_JAR%" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.parser ^
  -Dversion=%MAT_VERSION% ^
  -Dpackaging=jar >nul
if errorlevel 1 exit /b %errorlevel%

echo Installing org.eclipse.mat.hprof (%HPROF_JAR%)...
call mvn -B install:install-file ^
  -Dfile="%HPROF_JAR%" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.hprof ^
  -Dversion=%MAT_VERSION% ^
  -Dpackaging=jar >nul
if errorlevel 1 exit /b %errorlevel%

echo Installing org.eclipse.mat.report (%REPORT_JAR%)...
call mvn -B install:install-file ^
  -Dfile="%REPORT_JAR%" ^
  -DgroupId=org.eclipse.mat ^
  -DartifactId=org.eclipse.mat.report ^
  -Dversion=%MAT_VERSION% ^
  -Dpackaging=jar >nul
if errorlevel 1 exit /b %errorlevel%

echo === All Eclipse MAT %MAT_VERSION% dependencies installed successfully! ===
