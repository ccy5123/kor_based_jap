@echo off
REM Build KorJpnIme.dll with MSVC for full TSF/COM ABI compatibility.
REM Run from any directory; outputs to %TEMP%\KorJpnIme_msvc\.

setlocal EnableDelayedExpansion

set "VS_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VS_VCVARS%" (
    echo ERROR: vcvars64.bat not found at: %VS_VCVARS%
    exit /b 1
)

REM SRC_ROOT = directory containing this .bat
set "SRC_ROOT=%~dp0"
if "%SRC_ROOT:~-1%"=="\" set "SRC_ROOT=%SRC_ROOT:~0,-1%"

set "BUILD_DIR=%TEMP%\KorJpnIme_msvc"
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

echo Setting up MSVC environment...
call "%VS_VCVARS%" >nul
if errorlevel 1 (
    echo ERROR: Failed to set up MSVC environment
    exit /b 1
)

echo.
echo Building KorJpnIme.dll with MSVC cl.exe ...
cd /d "%BUILD_DIR%"

cl.exe /nologo /std:c++20 /O2 /EHsc /MD ^
    /D_UNICODE /DUNICODE /DWIN32_LEAN_AND_MEAN /DNOMINMAX ^
    /D_CRT_SECURE_NO_WARNINGS ^
    /I"%SRC_ROOT%\src" ^
    /I"%SRC_ROOT%\generated" ^
    /I"%SRC_ROOT%\.." ^
    /LD ^
    "%SRC_ROOT%\src\dllmain.cpp" ^
    "%SRC_ROOT%\src\KorJpnIme.cpp" ^
    "%SRC_ROOT%\src\KeyHandler.cpp" ^
    "%SRC_ROOT%\src\Composition.cpp" ^
    /Fe:KorJpnIme.dll ^
    /link ^
        /DEF:"%SRC_ROOT%\KorJpnIme.def" ^
        ole32.lib oleaut32.lib advapi32.lib uuid.lib

if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

echo.
echo BUILD OK: %BUILD_DIR%\KorJpnIme.dll
dir "%BUILD_DIR%\KorJpnIme.dll"
endlocal
