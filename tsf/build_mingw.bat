@echo off
:: Build KorJpnIme.dll using MinGW-W64 + CMake + Ninja
:: Run this from the project root (kor_based_jap/)

setlocal

set BUILD_DIR=tsf\build_mingw

:: Step 1: Generate mapping table from YAML (requires Python + PyYAML)
echo [1/3] Generating mapping_table.h...
python tsf\tools\gen_table.py
if errorlevel 1 (
    echo ERROR: gen_table.py failed. Install PyYAML: pip install pyyaml
    exit /b 1
)

:: Step 2: Configure with CMake (MinGW Makefiles or Ninja)
echo [2/3] Configuring CMake...
cmake -S tsf -B %BUILD_DIR% ^
    -G "Ninja" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_C_COMPILER=gcc ^
    -DCMAKE_CXX_COMPILER=g++
if errorlevel 1 (
    echo ERROR: CMake configure failed.
    exit /b 1
)

:: Step 3: Build
echo [3/3] Building DLL...
cmake --build %BUILD_DIR%
if errorlevel 1 (
    echo ERROR: Build failed.
    exit /b 1
)

echo.
echo ============================================================
echo  Build complete: %BUILD_DIR%\KorJpnIme.dll
echo.
echo  To register (requires admin):
echo    regsvr32 %BUILD_DIR%\KorJpnIme.dll
echo.
echo  To unregister:
echo    regsvr32 /u %BUILD_DIR%\KorJpnIme.dll
echo ============================================================
endlocal
