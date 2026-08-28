param(
    [string]$InstallDir = "$env:LOCALAPPDATA\RUZNAK\netscan"
)

$ErrorActionPreference = "Stop"
$sourceDir = Join-Path $PSScriptRoot "netscan"
$installRoot = Split-Path $InstallDir -Parent
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$shortcutDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
$appShortcut = Join-Path $shortcutDir "РУЗНАК netscan.lnk"
$uninstallShortcut = Join-Path $shortcutDir "Удалить РУЗНАК netscan.lnk"

if (-not (Test-Path (Join-Path $sourceDir "netscan.exe"))) {
    throw "Рядом с установщиком не найдена папка netscan"
}

# Освобождаем файлы и локальные порты. Настройки и сопряжения лежат отдельно
# в %USERPROFILE%\.netscan и при обновлении не затрагиваются.
Get-Process netscan -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 700

New-Item -ItemType Directory -Force $installRoot | Out-Null
if (Test-Path $InstallDir) {
    Remove-Item -LiteralPath $InstallDir -Recurse -Force
}
Copy-Item -LiteralPath $sourceDir -Destination $InstallDir -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "uninstall-netscan.ps1") `
    -Destination (Join-Path $installRoot "uninstall-netscan.ps1") -Force

$exe = Join-Path $InstallDir "netscan.exe"
New-Item -Path $runKey -Force | Out-Null
Set-ItemProperty -Path $runKey -Name "RUZNAK netscan" -Value ('"{0}"' -f $exe)

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($appShortcut)
$shortcut.TargetPath = $exe
$shortcut.WorkingDirectory = $InstallDir
$shortcut.Description = "РУЗНАК netscan — телефон вместо USB-сканера 2D"
$shortcut.Save()

$uninstall = Join-Path $installRoot "uninstall-netscan.ps1"
$shortcut = $shell.CreateShortcut($uninstallShortcut)
$shortcut.TargetPath = "powershell.exe"
$shortcut.Arguments = ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $uninstall)
$shortcut.Description = "Удалить РУЗНАК netscan"
$shortcut.Save()

Start-Process -FilePath $exe
Write-Host ""
Write-Host "РУЗНАК netscan установлен: $InstallDir" -ForegroundColor Green
Write-Host "Автозапуск включён для текущего пользователя."
