@echo off
REM ============================================================
REM  Fridge Prophet - build debug APK for a REAL PHONE
REM
REM  WHY THIS EXISTS:
REM  The debug build's built-in default API_BASE_URL is
REM  http://10.0.2.2:8000/ -- that address only works on the
REM  Android emulator. On a real phone the app cannot reach it,
REM  and the failure is SILENT: SplashScreen calls getProfile(),
REM  gets a network error, and falls back to the login screen.
REM  You end up staring at the login page thinking your account
REM  was wiped, when the real problem is just a wrong address.
REM
REM  This script detects your LAN IP and injects it.
REM
REM  HOW TO USE:
REM    1. Double-click start-backend.bat  (keep that window open)
REM    2. Double-click this file
REM    3. Install the APK it prints at the end
REM
REM  This file is deliberately pure ASCII and does NOT call chcp.
REM  Why: switching the code page in the middle of a .bat makes
REM  cmd.exe resume reading at a CHARACTER offset instead of a byte
REM  offset, so every line after the first non-ASCII character gets
REM  silently skipped. That is exactly how the JDK variable below
REM  came out empty the first time this script was written.
REM ============================================================

setlocal
cd /d "%~dp0"

set "JDK=G:\Android\jdk-17.0.20.1+1"
set "GRADLE=G:\Android\gradle-8.14.5\bin\gradle.bat"

REM ---------- 1. detect LAN IP ----------
REM Grab the first "IPv4 ... : a.b.c.d" line from ipconfig.
REM Note: on a Chinese Windows the label reads "IPv4 <address>",
REM but the token "IPv4" itself is ASCII, so findstr still matches.
REM Keep this file ASCII-only or the parsing breaks (see header).
set "LANIP="
for /f "tokens=2 delims=:" %%A in ('ipconfig ^| findstr /c:"IPv4"') do (
    if not defined LANIP set "LANIP=%%A"
)
if defined LANIP set "LANIP=%LANIP: =%"

if not defined LANIP (
    echo.
    echo [ERROR] Could not detect a LAN IP address.
    echo         Connect this computer to Wi-Fi and run again.
    echo.
    pause
    exit /b 1
)

echo ============================================================
echo  Fridge Prophet - building debug APK
echo ============================================================
echo  LAN IP   : %LANIP%
echo  API URL  : http://%LANIP%:8000/
echo.
echo  The backend must be running on THIS machine.
echo  If you have not started it yet, run start-backend.bat first.
echo ============================================================
echo.

REM ---------- 2. sanity checks ----------
if not exist "%JDK%\bin\java.exe" (
    echo [ERROR] JDK not found at: %JDK%
    echo         Edit the JDK variable at the top of this file.
    echo.
    pause
    exit /b 1
)
if not exist "%GRADLE%" (
    echo [ERROR] Gradle not found at: %GRADLE%
    echo         Edit the GRADLE variable at the top of this file.
    echo.
    pause
    exit /b 1
)

set "JAVA_HOME=%JDK%"

REM ---------- 3. build ----------
call "%GRADLE%" -p android assembleDebug -PAPI_BASE_URL=http://%LANIP%:8000/
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. Scroll up for the Gradle error.
    echo.
    pause
    exit /b 1
)

REM ---------- 4. copy APK next to this script ----------
set "APK=android\app\build\outputs\apk\debug\app-debug.apk"
set "OUT=outputs\fridge-prophet-debug-%LANIP%.apk"
if not exist "outputs" mkdir "outputs"
if exist "%APK%" (
    copy /y "%APK%" "%OUT%" >nul
    echo.
    echo ============================================================
    echo  DONE
    echo ============================================================
    echo  APK: %OUT%
    echo.
    echo  Install it with:
    echo    adb install -r "%OUT%"
    echo.
    echo  Then on the phone open the app. It should go straight to
    echo  the home screen. If you see the LOGIN page instead, the
    echo  phone cannot reach http://%LANIP%:8000/ -- check that both
    echo  devices are on the same Wi-Fi and the backend is running.
    echo ============================================================
) else (
    echo.
    echo [ERROR] Build reported success but no APK at %APK%
)

echo.
pause
