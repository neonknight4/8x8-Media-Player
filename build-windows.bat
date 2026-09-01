@echo off
REM ============================================================
REM  Builds the Windows installer ("KvizRadio-1.0.exe").
REM  Run this ON WINDOWS. Requirements:
REM    - JDK 21 (jpackage on PATH)
REM    - Maven (mvn on PATH)
REM    - WiX Toolset 3.x (candle.exe / light.exe on PATH)
REM  Output: dist\KvizRadio-1.0.exe
REM ============================================================

setlocal

set APP_NAME=KvizRadio
set JAR_NAME=KvizRadio
REM Verzija se moze zadati kao argument: build-windows.bat 1.1
set APP_VERSION=%1
if "%APP_VERSION%"=="" set APP_VERSION=1.0
set MODULE_NAME=its.kvizradio
set MAIN_MODULE=%MODULE_NAME%/its.kvizradio.Pokretac
set TOOLS_DIR=tools

REM VLC 3.x, ne 4.x - vlcj 4 radi sa libvlc 3, na 4 ne. Verzija je pinovana
REM namerno: instaler ne sme da se promeni sam od sebe kad VideoLAN objavi novo.
set VLC_VERSION=3.0.23
REM fpcalc (Chromaprint) pravi otisak zvuka za prepoznavanje pesme
set FPCALC_VERSION=1.5.1
set VLC_ZIP=vlc-%VLC_VERSION%-win64.zip
set VLC_URL=https://get.videolan.org/vlc/%VLC_VERSION%/win64/%VLC_ZIP%

echo [1/4] Maven build...
call mvn clean package || goto :error

echo [2/4] Checking bundled VLC...
REM Instalacija nosi svoj libvlc, kao sto HUB nosi yt-dlp i ffmpeg - inace
REM sviranje zavisi od toga da li je na tudjem laptopu instaliran VLC i koji.
if not exist "%TOOLS_DIR%\vlc\libvlc.dll" (
    echo   Downloading %VLC_ZIP% ...
    if not exist "%VLC_ZIP%" curl -L -o "%VLC_ZIP%" %VLC_URL% || goto :error
    if exist vlc-tmp rmdir /S /Q vlc-tmp
    powershell -NoProfile -Command "Expand-Archive -Force '%VLC_ZIP%' 'vlc-tmp'" || goto :error
    mkdir "%TOOLS_DIR%\vlc" 2>nul
    copy /Y "vlc-tmp\vlc-%VLC_VERSION%\libvlc.dll" "%TOOLS_DIR%\vlc\" >nul || goto :error
    copy /Y "vlc-tmp\vlc-%VLC_VERSION%\libvlccore.dll" "%TOOLS_DIR%\vlc\" >nul || goto :error
    REM plugins moraju da stoje pored dll-a: libvlc ih trazi relativno u odnosu
    REM na sebe, pa se bez tog foldera ne ucitava nijedan dekoder
    xcopy /E /I /Y /Q "vlc-tmp\vlc-%VLC_VERSION%\plugins" "%TOOLS_DIR%\vlc\plugins" >nul || goto :error
    rmdir /S /Q vlc-tmp
)

echo [2b/4] Checking fpcalc...
if not exist "%TOOLS_DIR%\fpcalc.exe" (
    echo   Downloading chromaprint-fpcalc-%FPCALC_VERSION% ...
    curl -L -o fpcalc.zip https://github.com/acoustid/chromaprint/releases/download/v%FPCALC_VERSION%/chromaprint-fpcalc-%FPCALC_VERSION%-windows-x86_64.zip || goto :error
    if exist fpcalc-tmp rmdir /S /Q fpcalc-tmp
    powershell -NoProfile -Command "Expand-Archive -Force 'fpcalc.zip' 'fpcalc-tmp'" || goto :error
    for /R fpcalc-tmp %%f in (fpcalc.exe) do copy /Y "%%f" "%TOOLS_DIR%\" >nul
    rmdir /S /Q fpcalc-tmp
)

echo [3/4] Copying app jar to module path...
copy /Y "target\%JAR_NAME%-*.jar" "target\libs\" >nul || goto :error
REM javafx-*.jar bez klasifikatora su prazne ljuske (300 bajtova) pored pravih
REM -win.jar verzija; na module path-u bi to bio isti modul dvaput
powershell -NoProfile -Command "Get-ChildItem target\libs\javafx-*.jar | Where-Object { $_.Name -notlike '*-win.jar' } | Remove-Item -Force" || goto :error

echo [4/4] jpackage...
REM --win-upgrade-uuid mora ostati ISTI zauvek - po njemu Windows prepoznaje
REM da je ovo ista aplikacija i radi update umesto druge instalacije.
REM
REM --add-modules jdk.crypto.ec: SunEC nije nicija "requires" nego service
REM provider, pa ga jlink ne uvlaci sam. Bez njega runtime nema nijedan ECDHE
REM cipher suite i HTTPS ka api.radio-browser.info pukne na handshake_failure.
if exist dist rmdir /S /Q dist
jpackage ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --win-upgrade-uuid d0cee49b-4869-4ae4-8c95-0778d93cec2b ^
  --icon "installer\kvizradio.ico" ^
  --module-path "target\libs" ^
  --module %MAIN_MODULE% ^
  --add-modules %MODULE_NAME%,jdk.crypto.ec ^
  --input "%TOOLS_DIR%" ^
  --dest dist ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --win-shortcut-prompt ^
  --win-per-user-install ^
  --description "Online radio za pab kviz - muzika izmedju rundi" ^
  --copyright "Mihailo Jankovic" ^
  --vendor "Mihailo Jankovic" || goto :error

echo.
echo DONE: dist\%APP_NAME%-%APP_VERSION%.exe
goto :eof

:error
echo.
echo BUILD FAILED
exit /b 1
