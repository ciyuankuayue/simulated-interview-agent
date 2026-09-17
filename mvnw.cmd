@echo off
setlocal EnableDelayedExpansion
set "BASEDIR=%~dp0"
set "DIST_DIR=%BASEDIR%.mvn\wrapper\dists"
set "MVN_HOME=%DIST_DIR%\apache-maven-3.9.9"
set "MVN_EXE=%MVN_HOME%\bin\mvn.cmd"
set "ZIP_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
set "ZIP_FILE=%DIST_DIR%\apache-maven-3.9.9-bin.zip"

if not exist "%MVN_EXE%" (
  if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
  if not exist "%ZIP_FILE%" (
    echo [mvnw] Downloading Apache Maven 3.9.9 ...
    powershell -NoProfile -Command "(New-Object System.Net.WebClient).DownloadFile('%ZIP_URL%','%ZIP_FILE%')"
  )
  for %%A in ("%ZIP_FILE%") do set "ZIP_SIZE=%%~zA"
  if !ZIP_SIZE! LSS 5000000 (
    echo [mvnw] ERROR: download too small ^(%ZIP_SIZE% bytes^) - network may have failed. Delete %ZIP_FILE% and retry.
    exit /b 1
  )
  echo [mvnw] Extracting Maven ...
  powershell -NoProfile -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%DIST_DIR%' -Force"
)

"%MVN_EXE%" %*
endlocal
