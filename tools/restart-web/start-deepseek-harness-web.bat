@echo off
setlocal

if /i "%~1"=="--help" (
    echo DeepSeek Harness Web restart launcher for Windows.
    echo.
    echo Usage:
    echo   %~nx0 "C:\path\to\deepseek-harness"
    echo   set DEEPSEEK_HARNESS_ROOT=C:\path\to\deepseek-harness
    echo   %~nx0
    exit /b 0
)

set "DSH_ROOT=%~1"
if not defined DSH_ROOT set "DSH_ROOT=%DEEPSEEK_HARNESS_ROOT%"
if not defined DSH_ROOT set "DSH_ROOT=%CD%"

if not exist "%DSH_ROOT%\apps\cli\src\bin.ts" (
    echo DeepSeek Harness root was not found: "%DSH_ROOT%"
    echo Pass the checkout path as the first argument or set DEEPSEEK_HARNESS_ROOT.
    pause
    exit /b 1
)

pushd "%DSH_ROOT%" || (
    echo Could not enter the DeepSeek Harness root: "%DSH_ROOT%"
    pause
    exit /b 1
)

where pnpm.cmd >nul 2>&1
if errorlevel 1 (
    echo pnpm was not found in PATH.
    echo Open a new PowerShell window once, then try this file again.
    popd
    pause
    exit /b 1
)

if not exist "apps\web\dist\index.html" (
    echo The Web frontend has not been built yet.
    echo Run: pnpm run build
    popd
    pause
    exit /b 1
)

echo Restarting DeepSeek Harness Web...
echo The existing instance on port 3080 will be stopped first.
echo.

rem Find the DSH process tree behind port 3080, stop it, and wait for the port.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$listener = Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if (-not $listener) { exit 0 }; $current = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $listener.OwningProcess); $rootPid = [int]$listener.OwningProcess; $isDsh = $false; while ($current) { $command = [string]$current.CommandLine; if ($command -match '(?i)deepseek-harness|start-deepseek-harness-web|dsh[.]js|corepack.*pnpm.*dsh\s+web|apps[/\\]cli[/\\]src[/\\]bin[.]ts.*\bweb\b') { $isDsh = $true; $rootPid = [int]$current.ProcessId }; if (-not $current.ParentProcessId) { break }; $parent = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $current.ParentProcessId); if (-not $parent) { break }; $current = $parent }; if (-not $isDsh) { Write-Error ('Port 3080 is owned by an unrecognized process; refusing to stop PID ' + $listener.OwningProcess + '.'); exit 1 }; Write-Host ('Stopping DSH process tree rooted at PID ' + $rootPid + '...'); & taskkill.exe /PID $rootPid /T /F | Out-Host; if ($LASTEXITCODE -ne 0) { exit 1 }; for ($i = 0; $i -lt 40; $i++) { if (-not (Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue)) { exit 0 }; Start-Sleep -Milliseconds 250 }; Write-Error 'Port 3080 was not released after stopping the previous DSH instance.'; exit 1"
if errorlevel 1 (
    echo Could not safely stop the previous DeepSeek Harness instance.
    popd
    pause
    exit /b 1
)

echo Starting DeepSeek Harness Web...
echo Keep this window open while using the Web UI.
echo.

call pnpm.cmd dsh web

set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" (
    echo.
    echo DeepSeek Harness exited with code %EXIT_CODE%.
    pause
)

exit /b %EXIT_CODE%
