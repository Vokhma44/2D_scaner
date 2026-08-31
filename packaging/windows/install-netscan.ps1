param(
    [string]$InstallDir = "$env:LOCALAPPDATA\RUZNAK\netscan",
    [string]$FleetServer = "https://fleet.ruznak.io",
    [string]$EnrollmentToken = "",
    [string]$AgentName = $env:COMPUTERNAME
)

$ErrorActionPreference = "Stop"
$sourceDir = Join-Path $PSScriptRoot "netscan"
$installRoot = Split-Path $InstallDir -Parent
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$shortcutDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
$appShortcut = Join-Path $shortcutDir "РУЗНАК netscan.lnk"
$uninstallShortcut = Join-Path $shortcutDir "Удалить РУЗНАК netscan.lnk"
$credentialsFile = Join-Path $env:USERPROFILE ".netscan\fleet-credentials.json"

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

if (-not (Test-Path $credentialsFile) -and [string]::IsNullOrWhiteSpace($EnrollmentToken)) {
    Write-Host ""
    Write-Host "Чтобы компьютер появился в панели fleet.ruznak.io, вставьте одноразовый код подключения." -ForegroundColor Yellow
    Write-Host "Код создаётся администратором кнопкой «Подключить агент»." -ForegroundColor Yellow
    $EnrollmentToken = Read-Host "Код подключения (Enter — установить автономно)"
}

$startArguments = @()
if (-not [string]::IsNullOrWhiteSpace($EnrollmentToken)) {
    $startArguments = @(
        "--fleet-server", $FleetServer,
        "--agent-name", $AgentName,
        "--enrollment-token", $EnrollmentToken.Trim()
    )
}
if ($startArguments.Count -gt 0) {
    Start-Process -FilePath $exe -ArgumentList $startArguments
} else {
    Start-Process -FilePath $exe
}
$EnrollmentToken = $null
Write-Host ""
Write-Host "РУЗНАК netscan установлен: $InstallDir" -ForegroundColor Green
Write-Host "Автозапуск включён для текущего пользователя."
if (Test-Path $credentialsFile) {
    Write-Host "Компьютер зарегистрирован на fleet-сервере." -ForegroundColor Green
} elseif ($startArguments.Count -gt 0) {
    Write-Host "Регистрация на fleet-сервере выполняется в фоне; компьютер появится в панели в течение минуты."
} else {
    Write-Host "Агент установлен автономно и не будет отображаться в fleet-панели." -ForegroundColor Yellow
}
