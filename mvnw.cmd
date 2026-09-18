@echo off
setlocal EnableDelayedExpansion
set "BASEDIR=%~dp0"

rem ============================================================
rem JDK selection: build JDK must be 17-23 (fixed JDK 21 on this machine; bytecode TARGET stays Java 17)
rem   1) if JAVA_HOME points to a usable JDK 17-23, respect it
rem   2) otherwise (unset / invalid / JDK 24+) fall back to %USERPROFILE%\jdk-21 when present
rem      (this machine has Machine-level JAVA_HOME=JDK24, set by its installer)
rem   3) final hard gate is maven-enforcer-plugin in pom.xml
rem ============================================================
set "JDK21_HOME=%USERPROFILE%\jdk-21"
set "JV_RAW="
set "JDK_MAJOR="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  rem parse major version via a temp file: nested quotes + pipe inside for/f break when JAVA_HOME contains spaces
  "%JAVA_HOME%\bin\java.exe" -version > "%TEMP%\mvnw-javaver.txt" 2>&1
  for /f "tokens=3" %%v in ('findstr /i "version" "%TEMP%\mvnw-javaver.txt"') do if not defined JV_RAW set "JV_RAW=%%~v"
  del /q "%TEMP%\mvnw-javaver.txt" >nul 2>&1
  for /f "tokens=1 delims=." %%m in ("!JV_RAW!") do set "JDK_MAJOR=%%m"
)
set "JDK_USABLE=0"
if defined JDK_MAJOR if !JDK_MAJOR! GEQ 17 if !JDK_MAJOR! LSS 24 set "JDK_USABLE=1"
if !JDK_USABLE!==0 (
  if exist "%JDK21_HOME%\bin\java.exe" (
    if defined JAVA_HOME (
      echo [mvnw] WARNING: JAVA_HOME=%JAVA_HOME% ^(JDK !JDK_MAJOR!^) is outside allowed range 17-23; falling back to %JDK21_HOME%
    ) else (
      echo [mvnw] JAVA_HOME not set; using %JDK21_HOME%
    )
    set "JAVA_HOME=%JDK21_HOME%"
  ) else (
    echo [mvnw] WARNING: no usable JDK ^(JAVA_HOME JDK=!JDK_MAJOR!, %JDK21_HOME% missing^); continuing with PATH
  )
) else (
  echo [mvnw] Using JAVA_HOME=%JAVA_HOME% ^(JDK !JDK_MAJOR!^)
)

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
