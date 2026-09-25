@echo off
REM ============================================================
REM  Fridge Prophet - signing keystore generator
REM
REM  HOW TO USE: just double-click this file.
REM
REM  Creates android\release.jks and android\keystore.properties.
REM  It will ask you for an alias and a password.
REM
REM  BEFORE YOU RUN: read the APK packaging guide (docs\04-*.md),
REM  sections 1 and 2.
REM  The keystore is the "official seal" of your app - if you lose
REM  it you can never update an already-published app again.
REM  Back it up to two separate places right after generating it.
REM
REM  Same wrapper pattern as start-backend.bat: the real logic is in
REM  tools\new-keystore.sh, which Windows cannot run by double-click.
REM ============================================================

REM  KEEP THIS FILE ASCII-ONLY. It calls chcp below, and a single
REM  non-ASCII character anywhere in the file makes cmd.exe resume
REM  reading at a character offset instead of a byte offset, which
REM  silently skips lines further down. That is not theoretical:
REM  a Chinese word in a comment here once made a variable come out
REM  empty and the script failed with a blank path in the error.
chcp 65001 >nul
setlocal

cd /d "%~dp0"

set "BASH="
for /d %%D in ("%USERPROFILE%\.workbuddy-ai\binaries\PortableGit\versions\*") do (
    if exist "%%D\usr\bin\bash.exe" set "BASH=%%D\usr\bin\bash.exe"
)
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not defined BASH for %%B in (bash.exe) do if not "%%~$PATH:B"=="" set "BASH=%%~$PATH:B"

if not defined BASH (
    echo.
    echo [ERROR] bash.exe not found. Install Git for Windows first:
    echo         https://git-scm.com/download/win
    echo.
    pause
    exit /b 1
)

if not exist "tools\new-keystore.sh" (
    echo.
    echo [ERROR] tools\new-keystore.sh not found.
    echo Make sure this .bat file sits in the project root, next to README.md.
    echo.
    pause
    exit /b 1
)

echo ============================================================
echo  Generate the Android signing keystore
echo ============================================================
echo  This will create:
echo    android\release.jks
echo    android\keystore.properties
echo.
echo  Both are already in .gitignore, so they will not be committed.
echo.
echo  You will be asked for:
echo    - an alias          (press Enter to accept the default)
echo    - an organisation   (can be anything, press Enter to skip)
echo    - a password        (twice, type at least 6 characters)
echo.
echo  Write the password down somewhere safe. There is no recovery.
echo ============================================================
echo.

"%BASH%" tools/new-keystore.sh

echo.
pause
