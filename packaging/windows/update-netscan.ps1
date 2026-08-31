param(
    [Parameter(Mandatory = $true)][string]$Package,
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [Parameter(Mandatory = $true)][string]$ExpectedVersion,
    [Parameter(Mandatory = $true)][int]$ParentPid
)

$ErrorActionPreference = "Stop"
$root = Split-Path $InstallDir -Parent
$staging = Join-Path $root "update-$ExpectedVersion"
$backup = Join-Path $root "backup-before-$ExpectedVersion"
$failed = Join-Path $root "failed-$ExpectedVersion"

try {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) {
        Stop-Process -Id $ParentPid -Force
        Start-Sleep -Milliseconds 800
    }

    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $staging | Out-Null
    Expand-Archive -LiteralPath $Package -DestinationPath $staging -Force
    $candidate = Join-Path $staging "netscan"
    if (-not (Test-Path (Join-Path $candidate "netscan.exe"))) { throw "В архиве отсутствует netscan.exe" }

    Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath $InstallDir -Destination $backup
    Move-Item -LiteralPath $candidate -Destination $InstallDir
    $process = Start-Process -FilePath (Join-Path $InstallDir "netscan.exe") -PassThru
    Start-Sleep -Seconds 15
    if ($process.HasExited) { throw "Новая версия завершилась сразу после запуска (код $($process.ExitCode))" }

    Remove-Item -LiteralPath $backup -Recurse -Force
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
} catch {
    $reason = $_.Exception.Message
    Get-Process netscan -ErrorAction SilentlyContinue | Stop-Process -Force
    if (Test-Path $InstallDir) {
        Remove-Item -LiteralPath $failed -Recurse -Force -ErrorAction SilentlyContinue
        Move-Item -LiteralPath $InstallDir -Destination $failed
    }
    if (Test-Path $backup) {
        Move-Item -LiteralPath $backup -Destination $InstallDir
        Start-Process -FilePath (Join-Path $InstallDir "netscan.exe")
    }
    $log = Join-Path $env:USERPROFILE ".netscan\update-error.log"
    "$(Get-Date -Format o) $reason" | Add-Content -LiteralPath $log -Encoding UTF8
    exit 1
}
