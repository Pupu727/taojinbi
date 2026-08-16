@echo off
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
set PYTHONUTF8=1
cd /d "%~dp0"

echo ============================================
echo   Taobao Double-11 Auto Tool
echo ============================================

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found in PATH. Install Python 3.7+ first.
    pause
    exit /b 1
)
echo [OK] Python ready

echo [..] Checking Python dependencies...
python -c "import uiautomator2" >nul 2>nul
if errorlevel 1 (
    echo [..] First run: installing dependencies, please wait...
    python -m pip install -r "%~dp0requirements.txt" -i https://pypi.tuna.tsinghua.edu.cn/simple
    if errorlevel 1 (
        echo [ERROR] Dependency install failed. Check network.
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
echo   Starting automation... keep screen on.
echo   Do NOT touch the phone while running.
echo   Logs are saved to logs\run_*.log
echo ============================================
python "main.py"

echo.
echo Script finished. Press any key to close.
pause >nul
