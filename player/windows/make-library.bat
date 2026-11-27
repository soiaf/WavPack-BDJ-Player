@echo off
if "%~1"=="" (
  echo Drag a WVPLAYER-xx folder onto this file, or run:  make-library.bat WVPLAYER-01
  pause
  exit /b 1
)
java -jar "%~dp0wvlibrary.jar" "%~1"
pause
