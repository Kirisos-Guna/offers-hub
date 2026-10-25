@echo off
rem Starts the Offers Hub API on port 8000, reachable from phones on the same Wi-Fi.
cd /d "%~dp0"
if not exist .venv (
    py -m venv .venv
    .venv\Scripts\python.exe -m pip install -r requirements.txt
)
.venv\Scripts\python.exe -m uvicorn server:app --host 0.0.0.0 --port 8000
