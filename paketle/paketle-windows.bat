@echo off
rem ============================================================
rem  Rift Surusu - Windows EXE paketleyici (Windows'ta calistir)
rem  Gerekenler: JDK 17+ (jpackage icinde gelir, https://adoptium.net)
rem  rift-surusu.jar bu klasorde ya da ..\target\ icinde olmali
rem  (Mac'te "mvn package" ile uretip buraya kopyalayabilirsin).
rem  Cikti: RiftSurusu-Windows.zip  ->  icindeki RiftSurusu.exe'ye cift tikla
rem ============================================================
setlocal
cd /d "%~dp0"

set JAR=rift-surusu.jar
if not exist "%JAR%" set JAR=..\target\rift-surusu.jar
if not exist "%JAR%" (
  echo rift-surusu.jar bulunamadi. Once "mvn package" calistir ve jar'i bu klasore koy.
  pause & exit /b 1
)
where jpackage >nul 2>nul || (
  echo jpackage bulunamadi. JDK 17 veya ustunu kur: https://adoptium.net  ^(JRE degil, JDK^)
  pause & exit /b 1
)

for /f "tokens=1 delims=." %%v in ('jpackage --version') do set JV=%%v
set EXTRA=
if %JV% GEQ 23 set EXTRA=--java-options "--sun-misc-unsafe-memory-access=allow"

if exist build rmdir /s /q build
mkdir build\input
copy /y "%JAR%" build\input\rift-surusu.jar >nul

echo JDK %JV% ile paketleniyor (1-2 dakika surebilir)...
jpackage --type app-image --name RiftSurusu --input build\input --main-jar rift-surusu.jar ^
  --main-class swarm.Main --dest build\dist --app-version 1.0.0 --vendor "Rift Surusu" ^
  --add-modules java.base,java.desktop,java.management,jdk.unsupported ^
  --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Djava.awt.headless=true" %EXTRA%
if errorlevel 1 ( echo Paketleme basarisiz. & pause & exit /b 1 )

if exist RiftSurusu-Windows.zip del RiftSurusu-Windows.zip
powershell -NoProfile -Command "Compress-Archive -Path 'build\dist\RiftSurusu' -DestinationPath 'RiftSurusu-Windows.zip'"
echo.
echo Hazir: %CD%\RiftSurusu-Windows.zip
echo Arkadasina bu zip'i gonder. Acip RiftSurusu klasorundeki RiftSurusu.exe'ye cift tiklasin (Java kurmaya gerek yok).
pause
