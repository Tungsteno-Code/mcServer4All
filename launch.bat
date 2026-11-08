@echo off
setlocal EnableExtensions EnableDelayedExpansion
title mcServer4all

REM Always run relative to the directory containing launch.bat
cd /d "%~dp0"

set "JAR_PATH=%~dp0mcServer4all.jar"
if not exist "%JAR_PATH%" (
    if exist "%~dp0mc-sync-launcher.jar" (
        set "JAR_PATH=%~dp0mc-sync-launcher.jar"
    ) else if exist "%~dp0target\mcServer4all.jar" (
        set "JAR_PATH=%~dp0target\mcServer4all.jar"
    ) else if exist "%~dp0target\mc-sync-launcher.jar" (
        set "JAR_PATH=%~dp0target\mc-sync-launcher.jar"
    ) else (
        echo [ERROR] mcServer4all.jar ^(o mc-sync-launcher.jar^) non trovato nella cartella corrente ne' in target\.
        echo Assicurati di aver estratto tutto lo zip o compilato il progetto con Maven.
        goto :fail
    )
)

REM 1. Check bundled portable Java 21 first (.tools\jdk-21\bin\java.exe)
set "JAVA_EXE="
if exist "%~dp0.tools\jdk-21\bin\java.exe" (
    set "JAVA_EXE=%~dp0.tools\jdk-21\bin\java.exe"
)

REM 2. Check JAVA_HOME
if not defined JAVA_EXE (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\java.exe" (
            call :check_java_ver "%JAVA_HOME%\bin\java.exe"
        )
    )
)

REM 3. Check system PATH
if not defined JAVA_EXE (
    for /f "delims=" %%P in ('where java.exe 2^>nul') do (
        if not defined JAVA_EXE call :check_java_ver "%%~fP"
    )
)

REM 4. Check common Windows JDK vendor folders
if not defined JAVA_EXE (
    for %%D in (
        "%ProgramFiles%\Eclipse Adoptium"
        "%ProgramFiles%\Java"
        "%ProgramFiles%\Microsoft"
        "%ProgramFiles%\BellSoft"
        "%ProgramFiles%\Amazon Corretto"
        "%ProgramFiles%\Zulu"
    ) do (
        if exist "%%~D" (
            for /f "delims=" %%J in ('dir /b /s "%%~D\java.exe" 2^>nul') do (
                if not defined JAVA_EXE call :check_java_ver "%%~fJ"
            )
        )
    )
)

REM 5. If Java 21+ is still not found, download and install portable Temurin 21 automatically!
if not defined JAVA_EXE (
    echo [INFO] Java 21+ non trovato sul PC. Avvio download e installazione automatica di OpenJDK 21...
    call :install_portable_java21
    if exist "%~dp0.tools\jdk-21\bin\java.exe" (
        set "JAVA_EXE=%~dp0.tools\jdk-21\bin\java.exe"
    )
)

if not defined JAVA_EXE (
    echo [ERROR] Impossibile trovare o scaricare automaticamente Java 21.
    echo Verifica la connessione Internet o installa manualmente Temurin 21 ^(https://adoptium.net^).
    goto :fail
)

REM Create config.json on first launch (can be edited directly in the GUI)
if "%~1"=="" (
    if not exist "%~dp0config.json" (
        "%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar "%JAR_PATH%" init --config "%~dp0config.json" >nul
    )
)

"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar "%JAR_PATH%" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] mcServer4all e' terminato con codice di errore %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%


REM ---------------------------------------------------------------------------
REM Subroutine: verifies if candidate java.exe is version >= 21
REM ---------------------------------------------------------------------------
:check_java_ver
set "CANDIDATE=%~1"
set "VER_STR="
set "JAVA_MAJOR="
for /f "tokens=3" %%V in ('"%CANDIDATE%" -version 2^>^&1 ^| findstr /i "version"') do (
    set "VER_STR=%%~V"
)
if defined VER_STR (
    for /f "tokens=1 delims=.-_" %%M in ("!VER_STR!") do (
        set "JAVA_MAJOR=%%M"
    )
)
if defined JAVA_MAJOR (
    if !JAVA_MAJOR! geq 21 (
        set "JAVA_EXE=%CANDIDATE%"
    )
)
goto :eof


REM ---------------------------------------------------------------------------
REM Subroutine: downloads portable Eclipse Temurin OpenJDK 21 into .tools\jdk-21
REM ---------------------------------------------------------------------------
:install_portable_java21
if not exist "%~dp0.tools" mkdir "%~dp0.tools"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; " ^
    "$url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'; " ^
    "$zip = Join-Path '%~dp0.tools' 'jdk21.zip'; " ^
    "$extract = Join-Path '%~dp0.tools' 'jdk21_tmp'; " ^
    "$dest = Join-Path '%~dp0.tools' 'jdk-21'; " ^
    "Write-Host '[INSTALLER] Download OpenJDK 21 (Eclipse Temurin x64)...'; " ^
    "Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing; " ^
    "Write-Host '[INSTALLER] Estrazione OpenJDK 21...'; " ^
    "if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }; " ^
    "Expand-Archive -Path $zip -DestinationPath $extract -Force; " ^
    "if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }; " ^
    "$inner = Get-ChildItem -Path $extract -Directory | Select-Object -First 1; " ^
    "Move-Item -Path $inner.FullName -Destination $dest -Force; " ^
    "Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue; " ^
    "Remove-Item $zip -Force -ErrorAction SilentlyContinue; " ^
    "Write-Host '[INSTALLER] Java 21 installato con successo in' $dest;"

if not exist "%~dp0.tools\jdk-21\bin\java.exe" (
    echo [WARN] Download portatile non riuscito, provo installazione con winget ^(richiede conferma UAC se richiesta^)...
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --silent --accept-package-agreements --accept-source-agreements
)
goto :eof

:fail
echo.
pause
exit /b 1
