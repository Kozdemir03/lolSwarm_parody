@echo off
rem Rift Surusu - Windows baslatici. rift-surusu.jar bu dosyayla ayni klasorde olmali.
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java bulunamadi. https://adoptium.net adresinden Java 17 veya ustunu kurun.
  pause
  exit /b 1
)
java -jar rift-surusu.jar
if errorlevel 1 pause
