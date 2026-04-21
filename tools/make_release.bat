@echo off
REM ----------------------------------------------------------------------
REM Build KorJpnIme.dll with MSVC and bundle a redistributable folder
REM containing everything install.ps1 needs.
REM
REM Usage:  make_release.bat [output_dir]
REM Default output dir:  C:\Temp\KorJpnIme_release
REM
REM Output structure:
REM     <output_dir>\
REM         KorJpnIme.dll
REM         jpn_dict.txt
REM         install_tip.reg
REM         uninstall_tip.reg
REM         install.ps1
REM         uninstall.ps1
REM         README.md
REM ----------------------------------------------------------------------
setlocal EnableDelayedExpansion

REM Always start from a real Windows directory (we may have been started from a UNC path).
cd /d C:\Temp

set "VS_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "PROJECT_ROOT=\\wsl.localhost\Ubuntu\home\cyjoe\kor_based_jap"
set "SRC_ROOT=%PROJECT_ROOT%\tsf"
set "BUILD_DIR=C:\Temp\KorJpnIme_msvc"

if "%~1"=="" ( set "OUT_DIR=C:\Temp\KorJpnIme_release" ) else ( set "OUT_DIR=%~1" )

if not exist "%VS_VCVARS%" (
    echo ERROR: vcvars64.bat not found at: %VS_VCVARS%
    echo Install Visual Studio 2022 Build Tools and try again.
    exit /b 1
)
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

call "%VS_VCVARS%" >nul 2>&1
if errorlevel 1 ( echo vcvars64 failed & exit /b 1 )

cd /d "%BUILD_DIR%"

echo === [1/3] Compiling KorJpnIme.dll with cl.exe ===
cl.exe /nologo /std:c++20 /O2 /EHsc /MD ^
    /D_UNICODE /DUNICODE /DWIN32_LEAN_AND_MEAN /DNOMINMAX /D_CRT_SECURE_NO_WARNINGS ^
    /I"%SRC_ROOT%\src" /I"%SRC_ROOT%\generated" /I"%SRC_ROOT%\.." ^
    /LD ^
    "%SRC_ROOT%\src\dllmain.cpp" ^
    "%SRC_ROOT%\src\KorJpnIme.cpp" ^
    "%SRC_ROOT%\src\KeyHandler.cpp" ^
    "%SRC_ROOT%\src\Composition.cpp" ^
    "%SRC_ROOT%\src\Dictionary.cpp" ^
    "%SRC_ROOT%\src\UserDict.cpp" ^
    "%SRC_ROOT%\src\CandidateWindow.cpp" ^
    /Fe:KorJpnIme.dll ^
    /link /DEF:"%SRC_ROOT%\KorJpnIme.def" ^
        ole32.lib oleaut32.lib advapi32.lib uuid.lib user32.lib gdi32.lib
if errorlevel 1 ( echo BUILD FAILED & exit /b 1 )

echo.
echo === [2/3] Bundling release folder %OUT_DIR% ===
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"
copy /Y "%BUILD_DIR%\KorJpnIme.dll"            "%OUT_DIR%\" >nul
copy /Y "%PROJECT_ROOT%\dict\jpn_dict.txt"     "%OUT_DIR%\" >nul
copy /Y "%SRC_ROOT%\install_tip.reg"           "%OUT_DIR%\" >nul
copy /Y "%SRC_ROOT%\uninstall_tip.reg"         "%OUT_DIR%\" >nul
copy /Y "%PROJECT_ROOT%\tools\install.ps1"     "%OUT_DIR%\" >nul
copy /Y "%PROJECT_ROOT%\tools\uninstall.ps1"   "%OUT_DIR%\" >nul
if exist "%PROJECT_ROOT%\README.md" (
    copy /Y "%PROJECT_ROOT%\README.md"          "%OUT_DIR%\" >nul
)

echo.
echo === [3/3] Release contents ===
dir /b "%OUT_DIR%"
echo.
echo OK: release ready at %OUT_DIR%
echo Run install.ps1 from that folder (PowerShell, will self-elevate).
endlocal
