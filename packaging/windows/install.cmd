@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-netscan.ps1"
if errorlevel 1 (
  echo.
  echo Установка завершилась с ошибкой.
  pause
  exit /b 1
)
echo.
pause
