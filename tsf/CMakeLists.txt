cmake_minimum_required(VERSION 3.28)
project(KorJpnIme LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Output: a Windows DLL registerable as a TSF text service
# ---------------------------------------------------------------------------
add_library(KorJpnIme SHARED
    src/dllmain.cpp
    src/KorJpnIme.cpp
    src/KeyHandler.cpp
    src/Composition.cpp
    src/pthread_stub.cpp   # Win32 FLS stubs — eliminates libwinpthread-1.dll dependency
)

# Module definition file — controls exported symbols (DllMain etc.)
set_target_properties(KorJpnIme PROPERTIES
    OUTPUT_NAME "KorJpnIme"
    SUFFIX      ".dll"
    PREFIX      ""
)

# The generated mapping table header lives here
target_include_directories(KorJpnIme PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/generated
    ${CMAKE_CURRENT_SOURCE_DIR}/..
)

# ---------------------------------------------------------------------------
# Windows-specific: link required system libraries
# ---------------------------------------------------------------------------
if(WIN32 OR MINGW)
    target_link_libraries(KorJpnIme PRIVATE
        ole32        # CoCreateInstance, COM infrastructure
        oleaut32     # BSTR, VARIANT helpers
        advapi32     # Registry (RegCreateKeyEx etc.)
    )

    # Keep Windows-style DLL exports visible
    target_compile_definitions(KorJpnIme PRIVATE
        _UNICODE
        UNICODE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
    )

    # DEF file so the linker exports DllGetClassObject etc. by name (no underscore mangling).
    # ld.exe cannot follow UNC paths (\\wsl.localhost\...), so copy the DEF to the local build dir.
    configure_file(
        ${CMAKE_CURRENT_SOURCE_DIR}/KorJpnIme.def
        ${CMAKE_CURRENT_BINARY_DIR}/KorJpnIme.def
        COPYONLY
    )
    set_target_properties(KorJpnIme PROPERTIES
        LINK_FLAGS "-Wl,--kill-at ${CMAKE_CURRENT_BINARY_DIR}/KorJpnIme.def"
    )
endif()

# ---------------------------------------------------------------------------
# Compiler warnings
# ---------------------------------------------------------------------------
if(MSVC)
    target_compile_options(KorJpnIme PRIVATE /W4 /WX)
else()
    target_compile_options(KorJpnIme PRIVATE
        -Wall -Wextra
        # TSF COM headers have unavoidable warnings under GCC/clang
        -Wno-unused-parameter
        -Wno-unknown-pragmas
        # COM pattern: Release() calls `delete this` on a non-virtual destructor — intentional
        -Wno-delete-non-virtual-dtor
        # Disable thread-safe static-local init (removes pthread_once from static initializers).
        # Safe: the IME runs in a single COM apartment thread; no concurrent static init occurs.
        -fno-threadsafe-statics
    )

    # Statically link MinGW runtime so ctfmon/system can load the DLL without
    # needing libgcc_s_seh-1.dll / libstdc++-6.dll on the system PATH.
    target_link_options(KorJpnIme PRIVATE
        -static-libgcc
        -static-libstdc++
        -static -lpthread
    )
endif()

# ---------------------------------------------------------------------------
# Custom target: regenerate the C++ mapping table from YAML
# ---------------------------------------------------------------------------
find_package(Python3 COMPONENTS Interpreter)
if(Python3_FOUND)
    add_custom_target(gen_table
        COMMAND ${Python3_EXECUTABLE}
                ${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_table.py
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/..
        COMMENT "Regenerating mapping_table.h from syllables.yaml"
    )
    add_dependencies(KorJpnIme gen_table)
endif()

# ---------------------------------------------------------------------------
# Install: place DLL next to the project root for easy regsvr32 testing
# ---------------------------------------------------------------------------
install(TARGETS KorJpnIme
    RUNTIME DESTINATION ${CMAKE_SOURCE_DIR}
)
