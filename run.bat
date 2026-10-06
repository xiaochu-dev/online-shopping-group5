@echo off
rem One-click start: Online Shopping MVP Group5 (port 8000)
cd /d "%~dp0"
python -m pip install -r requirements.txt
python main.py
pause
