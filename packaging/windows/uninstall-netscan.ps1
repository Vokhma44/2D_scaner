param(
    [string]$InstallDir = "$env:LOCALAPPDATA\RUZNAK\netscan",
    [switch]$RemoveUserData
)

$ErrorActionPreference = "Stop"
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$shortcutDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"

Get-Process netscan -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 700

Remove-ItemProperty -Path $runKey -Name "RUZNAK netscan" -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $shortcutDir "РУЗНАК netscan.lnk") -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $shortcutDir "Удалить РУЗНАК netscan.lnk") -Force -ErrorAction SilentlyContinue
if (Test-Path $InstallDir) {
    Remove-Item -LiteralPath $InstallDir -Recurse -Force
}
if ($RemoveUserData) {
    Remove-Item -LiteralPath (Join-Path $env:USERPROFILE ".netscan") -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "РУЗНАК netscan удалён." -ForegroundColor Green
if (-not $RemoveUserData) {
    Write-Host "Настройки и сопряжения сохранены в %USERPROFILE%\.netscan."
}
