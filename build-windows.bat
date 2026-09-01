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
set MAIN_CLASS=its.kvizradio.Pokretac
set TOOLS_DIR=tools

REM VLC 3.x, ne 4.x - vlcj 4 radi sa libvlc 3, na 4 ne. Verzija je pinovana
REM namerno: instaler ne sme da se promeni sam od sebe kad VideoLAN objavi novo.
set VLC_VERSION=3.0.23
REM Python 3.12 embeddable + shazamio: instaler nosi svoj Python, kao i svoj VLC.
REM 3.12, ne 3.13 - numpy i aiohttp imaju gotove cp312 wheel-ove za win_amd64,
REM pa pip nista ne kompajlira na build masini.
set PYTHON_VERSION=3.12.10
REM ffmpeg snima isecak strima za shazamio
set FFMPEG_VERSION=7.1.1
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

echo [2b/4] Checking bundled Python + shazamio...
REM Embeddable Python nema pip niti gleda site-packages dok se u ._pth fajlu
REM ne odkomentarise "import site" - bez toga "import shazamio" puca iako je
REM paket na disku.
if not exist "%TOOLS_DIR%\python\python.exe" (
    echo   Downloading python-%PYTHON_VERSION%-embed-amd64 ...
    curl -L -o python-embed.zip https://www.python.org/ftp/python/%PYTHON_VERSION%/python-%PYTHON_VERSION%-embed-amd64.zip || goto :error
    powershell -NoProfile -Command "Expand-Archive -Force 'python-embed.zip' '%TOOLS_DIR%\python'" || goto :error
    del python-embed.zip
    powershell -NoProfile -Command "Get-ChildItem '%TOOLS_DIR%\python\python*._pth' | ForEach-Object { (Get-Content $_) -replace '^#\s*import site', 'import site' | Set-Content $_ }" || goto :error
    curl -L -o get-pip.py https://bootstrap.pypa.io/get-pip.py || goto :error
    "%TOOLS_DIR%\python\python.exe" get-pip.py --no-warn-script-location || goto :error
    del get-pip.py
)
if not exist "%TOOLS_DIR%\python\Lib\site-packages\shazamio" (
    echo   Installing shazamio ...
    "%TOOLS_DIR%\python\python.exe" -m pip install --no-warn-script-location --only-binary=:all: shazamio || goto :error
)
REM provera da paket zaista moze da se ucita iz embeddable Pythona
"%TOOLS_DIR%\python\python.exe" -c "import shazamio" || goto :error

echo [2c/4] Checking ffmpeg...
REM Skripta zove ffmpeg da snimi isecak strima; Alati.alat("ffmpeg") ga nalazi
REM pored .exe-a. Iz zipa ide samo ffmpeg.exe - ffplay i ffprobe ne trebaju.
if not exist "%TOOLS_DIR%\ffmpeg.exe" (
    echo   Downloading ffmpeg-%FFMPEG_VERSION%-essentials ...
    curl -L -o ffmpeg.zip https://github.com/GyanD/codexffmpeg/releases/download/%FFMPEG_VERSION%/ffmpeg-%FFMPEG_VERSION%-essentials_build.zip || goto :error
    if exist ffmpeg-tmp rmdir /S /Q ffmpeg-tmp
    powershell -NoProfile -Command "Expand-Archive -Force 'ffmpeg.zip' 'ffmpeg-tmp'" || goto :error
    for /R ffmpeg-tmp %%f in (ffmpeg.exe) do copy /Y "%%f" "%TOOLS_DIR%\" >nul
    rmdir /S /Q ffmpeg-tmp
    del ffmpeg.zip
)

echo [3/4] Collecting jars and bundled tools...
REM ime jar-a je uvek KvizRadio-1.0.jar (verzija u pom-u), a --main-jar trazi
REM tacno ime - pa se prepisuje na stalno
copy /Y "target\%JAR_NAME%-*.jar" "target\libs\%JAR_NAME%.jar" >nul || goto :error
REM sve iz tools ide u isti --input folder: VLC, Python, ffmpeg i skripte pored .exe-a
xcopy /E /I /Y /Q "%TOOLS_DIR%\*" "target\libs\" >nul || goto :error

echo [4/4] jpackage...
REM --win-upgrade-uuid mora ostati ISTI zauvek - po njemu Windows prepoznaje
REM da je ovo ista aplikacija i radi update umesto druge instalacije.
REM
REM Aplikacija se pakuje preko classpath-a, ne kao modul: jaudiotagger nema
REM module-info, a jlink odbija automatske module. Zato --main-jar/--main-class,
REM i zato Pokretac ne nasledjuje Application (JVM to inace odbija sa classpath-a).
REM
REM --add-modules jdk.crypto.ec: SunEC nije nicija "requires" nego service
REM provider, pa ga jlink ne uvlaci sam. Bez njega runtime nema nijedan ECDHE
REM cipher suite i HTTPS ka api.radio-browser.info pukne na handshake_failure.
REM
REM --add-modules jdk.unsupported: JavaFX-ov Marlin rasterizer trazi
REM sun.misc.Unsafe. Bez njega se aplikacija digne pa pukne na prvom crtanju -
REM NoClassDefFoundError: sun/misc/Unsafe u com.sun.marlin.
if exist dist rmdir /S /Q dist
jpackage ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --win-upgrade-uuid d0cee49b-4869-4ae4-8c95-0778d93cec2b ^
  --icon "installer\kvizradio.ico" ^
  --input "target\libs" ^
  --main-jar %JAR_NAME%.jar ^
  --main-class %MAIN_CLASS% ^
  --add-modules java.base,java.desktop,java.net.http,java.logging,jdk.crypto.ec,jdk.unsupported ^
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
