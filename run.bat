@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
set PYTHONUTF8=1
cd /d "%~dp0"

echo ============================================
echo   Taojinbi - Taobao Coin Auto
echo ============================================

set "PYTHON="

REM 1) Prefer py launcher
where py >nul 2>nul
if not errorlevel 1 (
  py -3 -c "import sys" >nul 2>nul
  if not errorlevel 1 (
    set "PYTHON=py -3"
    goto :have_python
  )
)

REM 2) Scan "where python", skip Microsoft Store stubs
for /f "delims=" %%I in ('where python 2^>nul') do (
  echo %%I | find /I "\WindowsApps\" >nul
  if errorlevel 1 (
    "%%I" -c "import sys" >nul 2>nul
    if not errorlevel 1 (
      set "PYTHON=%%I"
      goto :have_python
    )
  )
)

echo.
echo [ERROR] Real Python was NOT found on this PC.
echo.
echo   This is NOT missing project dependencies.
echo   Windows only has a Microsoft Store placeholder for "python".
echo.
echo   Install Python first:
echo   1. Open: https://www.python.org/downloads/
echo   2. Install Python 3.11 or 3.12
echo   3. CHECK the box: Add python.exe to PATH
echo   4. Close this window, open a NEW terminal, run run.bat again
echo.
echo   Or run in PowerShell:
echo     winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements
echo.
echo   Optional: Settings ^> Apps ^> Advanced app settings ^> App execution aliases
echo   Turn OFF python.exe / python3.exe (Store stubs).
echo.
pause
exit /b 1

:have_python
echo [OK] Python ready: !PYTHON!
!PYTHON! -c "import sys; print('     version', sys.version.split()[0])"

echo [..] Checking Python dependencies...
!PYTHON! -c "import uiautomator2" >nul 2>nul
if errorlevel 1 (
    echo [..] First run: installing dependencies, please wait...
    echo [..] Using Tsinghua mirror. If stuck, close VPN/proxy and retry.
    !PYTHON! -m pip install -r "%~dp0requirements.txt" -i https://pypi.tuna.tsinghua.edu.cn/simple --timeout 30 --retries 3
    if errorlevel 1 (
        echo [ERROR] Dependency install failed. Check network, close VPN, then retry.
        pause
        exit /b 1
    )
)
echo [OK] Dependencies ready

if not exist "%~dp0platform-tools\adb.exe" (
    echo [ERROR] platform-tools\adb.exe not found.
    pause
    exit /b 1
)
set "PATH=%~dp0platform-tools;%PATH%"
echo [OK] ADB ready

echo [..] Checking USB device...
set "DEVICE_FOUND="
for /f "skip=1 tokens=1,2" %%a in ('adb devices 2^>nul') do (
    if "%%b"=="device" set "DEVICE_FOUND=1"
)
if not defined DEVICE_FOUND (
    echo.
    echo [ERROR] No Android phone detected!
    echo.
    echo   Please check all three:
    echo   1. Phone is connected to PC via USB cable
    echo   2. USB debugging is ON: Settings ^> Developer options
    echo   3. On the phone, tap "Allow" on the USB debugging popup
    echo.
    echo   Then run this script again.
    echo.
    pause
    exit /b 1
)

echo [OK] Device connected.
echo ============================================
echo   Starting Taojinbi... keep screen on.
echo   Do NOT touch the phone while running.
echo   Logs are saved to logs\run_*.log
echo ============================================
!PYTHON! "taojinbi.py"

echo.
echo Script finished. Press any key to close.
pause >nul
