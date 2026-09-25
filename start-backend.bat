@echo off
REM ============================================================
REM  Fridge Prophet - backend launcher
REM
REM  HOW TO USE: just double-click this file.
REM
REM  Why this wrapper exists:
REM  The real work is done by tools\start-backend.sh, which is a bash
REM  script. Windows cannot run .sh files by double-clicking, so this
REM  wrapper locates a bash.exe and calls the script with it.
REM
REM  Note on paths: every path below is derived from %~dp0 (this
REM  file's own folder) or from environment variables. Nothing is
REM  hardcoded, because the project lives under a folder whose name
REM  contains non-ASCII characters, and hardcoding it in a .bat
REM  risks encoding corruption (the file is UTF-8, cmd reads GBK).
REM ============================================================

chcp 65001 >nul
setlocal

REM Switch to the folder this .bat lives in (the project root)
cd /d "%~dp0"

REM ---------- Locate bash.exe ----------
set "BASH="

REM 1. Bundled PortableGit shipped with WorkBuddy (any version folder)
for /d %%D in ("%USERPROFILE%\.workbuddy-ai\binaries\PortableGit\versions\*") do (
    if exist "%%D\usr\bin\bash.exe" set "BASH=%%D\usr\bin\bash.exe"
)

REM 2. A normally installed Git for Windows
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"

REM 3. Whatever bash is already on PATH
if not defined BASH for %%B in (bash.exe) do if not "%%~$PATH:B"=="" set "BASH=%%~$PATH:B"

if not defined BASH (
    echo.
    echo [ERROR] bash.exe not found. Looked in:
    echo   1. %%USERPROFILE%%\.workbuddy-ai\binaries\PortableGit\versions\*\usr\bin\bash.exe
    echo   2. %%ProgramFiles%%\Git\bin\bash.exe
    echo   3. %%LOCALAPPDATA%%\Programs\Git\bin\bash.exe
    echo   4. bash.exe on PATH
    echo.
    echo Install Git for Windows from https://git-scm.com/download/win
    echo and run this file again.
    echo.
    pause
    exit /b 1
)

if not exist "tools\start-backend.sh" (
    echo.
    echo [ERROR] tools\start-backend.sh not found.
    echo Make sure this .bat file sits in the project root, next to README.md.
    echo.
    echo Current folder: %CD%
    echo.
    pause
    exit /b 1
)

echo ============================================================
echo  Fridge Prophet - starting backend
echo ============================================================
echo  bash:    %BASH%
echo  workdir: %CD%
echo.
echo  The first run installs dependencies and may take 1-3 minutes.
echo  Later runs start in a few seconds.
echo.
echo  When it is up, open:  http://127.0.0.1:8000/docs
echo  Press Ctrl+C in this window to stop the server.
echo ============================================================
echo.

"%BASH%" tools/start-backend.sh

echo.
echo [Backend has stopped]
pause
