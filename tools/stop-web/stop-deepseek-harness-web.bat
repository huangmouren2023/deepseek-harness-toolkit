@echo off
setlocal

if /i "%~1"=="--help" (
    echo DeepSeek Harness Web stop-only script for Windows.
    echo.
    echo Usage:
    echo   %~nx0
    exit /b 0
)

echo Stopping DeepSeek Harness Web on 127.0.0.1:3080...

rem Find the DSH process tree behind port 3080, stop it, and wait for the port.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$listener = Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if (-not $listener) { Write-Host 'DeepSeek Harness is already stopped.'; exit 0 }; $current = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $listener.OwningProcess); $rootPid = [int]$listener.OwningProcess; $isDsh = $false; while ($current) { $command = [string]$current.CommandLine; if ($command -match '(?i)deepseek-harness|start-deepseek-harness-web|stop-deepseek-harness-web|dsh[.]js|corepack.*pnpm.*dsh\s+web|apps[/\\]cli[/\\]src[/\\]bin[.]ts.*\bweb\b') { $isDsh = $true; $rootPid = [int]$current.ProcessId }; if (-not $current.ParentProcessId) { break }; $parent = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $current.ParentProcessId); if (-not $parent) { break }; $current = $parent }; if (-not $isDsh) { Write-Error ('Port 3080 is owned by an unrecognized process; refusing to stop PID ' + $listener.OwningProcess + '.'); exit 1 }; Write-Host ('Stopping DSH process tree rooted at PID ' + $rootPid + '...'); & taskkill.exe /PID $rootPid /T /F | Out-Host; if ($LASTEXITCODE -ne 0) { exit 1 }; for ($i = 0; $i -lt 40; $i++) { if (-not (Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue)) { Write-Host 'DeepSeek Harness stopped; port 3080 is free.'; exit 0 }; Start-Sleep -Milliseconds 250 }; Write-Error 'Port 3080 was not released after stopping DeepSeek Harness.'; exit 1"
if errorlevel 1 (
    echo DeepSeek Harness was not stopped safely.
    pause
    exit /b 1
)

exit /b 0
