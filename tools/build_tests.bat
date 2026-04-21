@echo off
REM ----------------------------------------------------------------------
REM Build and run tsf_tests.exe -- the project's minimal unit-test runner.
REM
REM The tests cover the pure (non-COM, non-TSF) pieces of the engine --
REM HangulComposer, BatchimLookup, and a smoke-level check of Viterbi.
REM Nothing here needs the binary kj_*.bin data files or ctfmon to be
REM running, so this is safe to execute on any dev machine.
REM
REM Usage: build_tests.bat
REM
REM Output:
REM   %TEMP%\KorJpnIme_tests\tsf_tests.exe     (compiled test binary)
REM   exit code 0 iff every test case passed
REM ----------------------------------------------------------------------
setlocal EnableDelayedExpansion

cd /d C:\Temp

set "VS_VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "PROJECT_ROOT=\\wsl.localhost\Ubuntu\home\cyjoe\kor_based_jap"
set "SRC_ROOT=%PROJECT_ROOT%\tsf"
set "BUILD_DIR=%TEMP%\KorJpnIme_tests"

REM  The `(x86)` inside VS_VCVARS trips up cmd.exe's parser if we put echoes
REM  referencing it inside an `if (...)` block, so use goto/label instead.
if not exist "%VS_VCVARS%" goto no_vcvars
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

call "%VS_VCVARS%" >nul 2>&1
if errorlevel 1 goto vcvars_failed
goto vcvars_ok
:no_vcvars
echo ERROR: vcvars64.bat not found -- install Visual Studio 2022 Build Tools.
exit /b 1
:vcvars_failed
echo ERROR: vcvars64 call failed.
exit /b 1
:vcvars_ok

cd /d "%BUILD_DIR%"

echo === [1/2] Compiling tsf_tests.exe ===
cl.exe /nologo /std:c++20 /O2 /EHsc /MD ^
    /D_UNICODE /DUNICODE /DWIN32_LEAN_AND_MEAN /DNOMINMAX /D_CRT_SECURE_NO_WARNINGS ^
    /I"%SRC_ROOT%\src" /I"%SRC_ROOT%\generated" /I"%SRC_ROOT%\.." ^
    "%SRC_ROOT%\tests\test_main.cpp" ^
    "%SRC_ROOT%\tests\test_hangul_composer.cpp" ^
    "%SRC_ROOT%\tests\test_batchim_lookup.cpp" ^
    "%SRC_ROOT%\tests\test_viterbi.cpp" ^
    "%SRC_ROOT%\src\HangulComposer.cpp" ^
    "%SRC_ROOT%\src\RichDictionary.cpp" ^
    "%SRC_ROOT%\src\Connector.cpp" ^
    "%SRC_ROOT%\src\Viterbi.cpp" ^
    /Fe:tsf_tests.exe

if errorlevel 1 ( echo BUILD FAILED & exit /b 1 )

echo.
echo === [2/2] Running tsf_tests.exe ===
"%BUILD_DIR%\tsf_tests.exe"
set RC=%ERRORLEVEL%
echo.
if %RC% equ 0 ( echo ALL TESTS PASSED ) else ( echo TESTS FAILED: exit=%RC% )
exit /b %RC%
