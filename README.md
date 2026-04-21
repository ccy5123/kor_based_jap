# Korean-Japanese IME (KorJpnIme)

Type Japanese kana on a Korean 2-beolsik keyboard layout. A Windows TSF (Text
Services Framework) IME that converts Korean syllables (가, 카, 산…) into
Japanese kana (が, か, さん…).

## Status

✅ Phase 1 — Mapping rules + Python proof-of-concept
✅ Phase 2 — TSF C++ skeleton (DLL, COM registration, key sink)
✅ Phase 3 — Working IME: Korean syllables → kana commit, with preedit underline
🚧 Phase 4 — Kanji conversion (kana → 漢字 candidate window) — in progress

### Working today

- Type with Korean 2-beolsik layout, output in Japanese hiragana
- Preedit underline while composing (e.g. ㅋ→ㅋ→카→か commit on next consonant)
- Per-syllable commit (ka·na typing produces かな progressively)
- F6/F7 toggle hiragana/katakana commit
- Hyphen → ー (long-vowel mark)
- 한자 (VK_CONVERT) / 한영 (VK_NONCONVERT) toggle IME on/off

### Not yet

- Kana accumulation buffer (standard JP-IME pattern: type until Space then convert)
- Kanji conversion candidate window
- User dictionary / learning
- Display attribute customisation (preedit colour)

## Build

### Prerequisites

- Windows 10/11 with Visual Studio 2022 Build Tools installed
- The build script auto-locates `vcvars64.bat`, MSVC `cl.exe`, and the bundled
  CMake + Ninja from VS Build Tools

### Steps

```cmd
cd tsf
build_msvc.bat
```

Output: `%TEMP%\KorJpnIme_msvc\KorJpnIme.dll` (~43 KB)

### One-shot release build

```cmd
tools\make_release.bat
```

Produces `C:\Temp\KorJpnIme_release\` with everything needed (DLL,
dictionary, .reg files, install/uninstall scripts).  Zip it up and
distribute as-is.

### Install

```powershell
# From the release folder (or anywhere with KorJpnIme.dll + install_tip.reg next to install.ps1):
powershell -ExecutionPolicy Bypass -File install.ps1
```

The script:
1. Self-elevates (UAC prompt).
2. Copies files to `C:\Program Files\KorJpnIme\`.
3. `regsvr32` the DLL.
4. `reg import install_tip.reg` (TIP registered with `Enable=0`).
5. Tells you to log out / in, then add the IME via Settings UI.

After login: **Settings → Time & Language → Language → Korean → Language options → Add a keyboard → Korean-Japanese IME**.

Use **Win + Space** to switch input methods.

### Uninstall

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\KorJpnIme\uninstall.ps1"
# Add -RemoveFiles to also delete the install directory
```

## Architecture

```
mapping/syllables.yaml     (Korean syllable → kana table, 114 entries)
   ↓ tsf/tools/gen_table.py
tsf/generated/
   ├── mapping_table.h     (constexpr sorted Entry[] for binary search)
   └── batchim_rules.h     (받침 → kana suffix rules, ㄴ/ㅁ/ㅇ→ん etc.)

tsf/src/
   ├── dllmain.cpp         (DLL entry, IClassFactory, DllRegisterServer/Unregister)
   ├── KorJpnIme.cpp/.h    (ITfTextInputProcessor — IME root COM object)
   ├── KeyHandler.cpp/.h   (ITfKeyEventSink + HangulComposer state machine)
   ├── Composition.cpp/.h  (preedit + commit via TSF edit sessions)
   ├── BatchimLookup.h     (single-syllable Korean → kana with batchim rules)
   ├── DebugLog.h          (file logger to C:\KorJpnIme*\debug.log)
   └── Globals.h           (CLSIDs, GUIDs, Module refs)

tsf/install_tip.reg        (TSF TIP profile — Enable=0, Categories included)
tsf/uninstall_tip.reg      (removes TSF TIP entries)
tsf/build_msvc.bat         (one-shot MSVC build script)
```

### Why MSVC (not MinGW)?

Initial development used MinGW-W64. Because TSF / ctfmon assume MSVC ABI,
the MinGW-built DLL caused subtle vtable / heap mismatches that crashed
ctfmon and broke system-wide keyboard input. Switching to MSVC
(VS 2022 Build Tools) eliminated all ABI-related issues.

### Why `bEnabledByDefault=FALSE` + `Enable=0` + manual `install_tip.reg`?

ctfmon hot-reloads any new TSF TIP it sees during runtime. If the TIP is
registered by the IME's own `DllRegisterServer` (via TSF API), ctfmon
immediately injects the DLL into every TSF-using process and makes it the
active TIP — displacing the standard 한국어 IME and breaking Korean input.

To avoid this:
- `DllRegisterServer` registers ONLY the COM CLSID — no TSF API calls.
- The TSF TIP profile is added by a separate `install_tip.reg` with `Enable=0`
  and full Category metadata (matching what MS-IME registers).
- The user must log out / log in for ctfmon to pick the new TIP up cleanly.
- The user explicitly enables the IME via Windows Settings.

## License

TBD
