@echo off
REM One-click build for online-shopping-group5 (Windows)
REM Runs: install deps -> run all tests -> package dist zip
cd /d "%~dp0"

echo ============================================
echo  Build online-shopping-group5 v1.0.0
echo ============================================

if not exist ".venv\Scripts\python.exe" (
    echo [1/3] Creating virtual environment .venv ...
    python -m venv .venv
    if errorlevel 1 goto :failed
)

echo [2/3] Installing dependencies ...
".venv\Scripts\python.exe" -m pip install -q -r requirements-dev.txt
if errorlevel 1 goto :failed

echo [3/3] Building ...
".venv\Scripts\python.exe" build.py
if errorlevel 1 goto :failed

echo.
echo Done. Output: dist\
pause
exit /b 0

:failed
echo.
echo BUILD FAILED. See messages above.
pause
exit /b 1
