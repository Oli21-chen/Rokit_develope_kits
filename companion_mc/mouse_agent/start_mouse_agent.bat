@echo off
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [mouse_agent] Virtual env not found in %~dp0
    echo.
    echo One-time setup:
    echo   python -m venv .venv
    echo   .venv\Scripts\pip install -r requirements.txt
    echo.
    pause
    exit /b 1
)

echo [mouse_agent] Starting...
echo   1. Sensitivity window should open - click green ARM
echo   2. OR in THIS window: type  a  and press Enter to ARM
echo.

".venv\Scripts\python.exe" mouse_agent.py --port 9460 --frame-port 9461 --token "dev-token" --gui --auto-arm --laptop-inference
set EXITCODE=%ERRORLEVEL%

echo.
if %EXITCODE% NEQ 0 (
    echo [mouse_agent] Exited with error %EXITCODE%.
) else (
    echo [mouse_agent] Stopped.
)
pause
exit /b %EXITCODE%
