@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup-PC.ps1" -StartSayIt
if errorlevel 1 pause
