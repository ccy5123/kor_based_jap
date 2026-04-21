# KorJpnIme — Korean keyboard, Japanese output

[![Release](https://img.shields.io/github/v/release/ccy5123/kor_based_jap?display_name=tag&sort=semver)](https://github.com/ccy5123/kor_based_jap/releases/latest)
[![License](https://img.shields.io/github/license/ccy5123/kor_based_jap)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/ccy5123/kor_based_jap/tests.yml?branch=main&label=tests)](https://github.com/ccy5123/kor_based_jap/actions/workflows/tests.yml)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-blue)](https://github.com/ccy5123/kor_based_jap/releases/latest)
[![Stars](https://img.shields.io/github/stars/ccy5123/kor_based_jap?style=social)](https://github.com/ccy5123/kor_based_jap/stargazers)

A Windows TSF (Text Services Framework) input method that lets you type
Japanese on a **Korean 2-beolsik keyboard layout**.  You type Korean
syllables (와타시노, 갓코우, 한바아가아…) and KorJpnIme converts them
to Japanese kana, then offers kanji candidates with a Mozc-quality
viterbi-based segmentation engine.

> 🌏 Languages: **English** · [한국어](README.ko.md) · [日本語](README.ja.md)

```
와타시  +  Space   →   私 / 渡し / 渡 / ワタシ / わたし
와타시노 + Space   →   私の / 渡しの / わたしの / ワタシノ
갓코우  +  Space   →   学校 / 月光 / がっこう / ガッコウ
ㅇㅗㅗ  (no Space) →   を       (object particle)
ㅇㅘㅏ  (no Space) →   は       (topic particle)
ㅇㅔㅔ  (no Space) →   へ       (direction particle)
한바아가아 + Space →   ハンバーガー (auto-katakana fallback)
```

No mode toggling, no romaji shim, no extra hotkeys to remember.  The
candidate window pops up next to your caret like every other JP IME on
the platform.

---

## Features

- **Korean 2-beolsik layout → Japanese kana** with full compound vowel
  (ㅗ+ㅏ=ㅘ → わ) and compound jong (ㄴ+ㅈ=ㄵ → with proper migration)
  handling.
- **Hatsuon ん / sokuon っ** auto-detected from Korean batchim
  patterns (ㄴㅁㅇ → ん, ㅎ → っ, ㅅㅆ at terminal or before consonants
  → っ, etc.).
- **Mozc OSS dictionary** (~750k unique kana keys, ~1.29 M entries) for
  kanji candidates with proper Mozc cost values.
- **Viterbi top-K segmentation** powered by Mozc's bigram connection
  cost matrix.  Multi-word phrases like わたしの decompose into
  segmented candidates (`私の`, `渡しの`, ...) at the top of the
  candidate list.
- **Verb conjugation support** via Mozc's suffix table — `たべます`,
  `よみました`, `きれいです` segment correctly even when the full
  inflected form is not in the main dictionary.
- **Auto-katakana fallback** for loanwords whose dictionary path
  doesn't reach kanji (`ハンバーガー` for `한바아가아`).
- **Three Japanese-particle keystroke patterns** that have no native
  Korean meaning, repurposed:
  - `ㅇ-ㅗ-ㅗ` → を   (object, "wo" sound)
  - `ㅇ-ㅘ-ㅏ` → は   (topic,  "wa" sound)
  - `ㅇ-ㅔ-ㅔ` → へ   (direction, "e" sound)

  Real long-vowel forms (おお, ええ) are unaffected because they
  require an explicit ㅇ between the vowels.
- **Per-user learning dictionary** — every kanji you select bubbles to
  the top of the list next time.  Capped at 5000 entries by default
  (LFU eviction); knob in `settings.ini`.
- **Settings hot-reload** — edits to `%APPDATA%\KorJpnIme\settings.ini`
  apply on the next keystroke.  No log-out required.
- **Persistent katakana toggle** (configurable, default `RAlt+K`)
  forces katakana output for the next sequence of keys.
- **Custom display attribute** — preedit text rendered with a dotted
  blue underline matching MS-IME / Mozc visual style.

## Quickstart

### Install

1. Download the latest `KorJpnIme-vX.Y.Z.zip` from the GitHub release
   page (or build from source — see below).
2. Unzip anywhere.
3. From an admin PowerShell, run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File install.ps1
   ```
   The script self-elevates if needed.  It copies files to
   `C:\Program Files\KorJpnIme\`, registers the COM server, and
   imports the TSF profile registry entries.

4. **Log out and log back in.**  ctfmon caches IME profiles per
   session and only picks up new ones cleanly across a fresh session.

5. Settings → Time & Language → Language → Korean → Language options
   → Add a keyboard → **Korean-Japanese IME**.

6. Press `Win+Space` to switch to it whenever you want to type
   Japanese.

### Uninstall

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\KorJpnIme\uninstall.ps1"
# add  -RemoveFiles  to also delete the install directory
```

## Configuration

Settings live at `%APPDATA%\KorJpnIme\settings.ini` (auto-created on
first run with the documented defaults).  Edit, save, and the IME
picks up changes on the next keystroke.

```ini
[Hotkeys]
KatakanaToggle = RAlt+K          ; modifiers: Ctrl Shift Alt LAlt RAlt Win

[Behavior]
FullWidthAscii      = true       ; type 1 → １ etc. while IME is on
UserDictLearn       = true       ; remember every kanji you pick
UserDictMaxEntries  = 5000       ; LFU eviction cap; 0 = unlimited
```

## Build from source

### Prerequisites

- Windows 10/11
- Visual Studio 2022 Build Tools (MSVC v143 + Windows 10 SDK)
- Python 3.10+ (only for the data builder, not for runtime)

### Steps

```cmd
:: 1. Pull Mozc OSS data sources (~95 MB of TSV)
cd dict\mozc_src
fetch.sh           :: bash; or run inside WSL

:: 2. Build the binary engine inputs (~71 MB output)
cd ..
python build_viterbi_data.py

:: 3. Build the DLL + bundle a release folder
cd ..
tools\make_release.bat
```

Output: `C:\Temp\KorJpnIme_release\` with the DLL, dictionaries,
.reg files, install/uninstall scripts, and `LICENSES.txt`.  Zip it up
and ship.

### Tests

```cmd
tools\build_tests.bat
```

Builds and runs the 56-case unit suite (HangulComposer, BatchimLookup,
Viterbi smoke).  Exit code 0 iff every test passed.

## Architecture

```
mapping/syllables.yaml        Korean syllable → kana table (~150 entries)
   ↓ tsf/tools/gen_table.py
tsf/generated/
   ├── mapping_table.h        constexpr sorted Entry[] for binary search
   └── batchim_rules.h        받침 → kana suffix rules (sokuon / hatsuon)

dict/
   ├── jpn_dict.txt           legacy text dict (kana → kanji TSV, 18 MB)
   ├── kj_dict.bin            rich dict with lid/rid/cost (binary, 57 MB)
   ├── kj_conn.bin            Mozc bigram cost matrix (binary, 14 MB)
   ├── LICENSES.txt           NAIST IPAdic + Mozc + Okinawa attributions
   ├── build_viterbi_data.py  builds kj_*.bin from Mozc OSS sources
   └── build_dict_mozc.py     builds jpn_dict.txt from same sources

tsf/src/
   ├── dllmain.cpp            DLL entry, IClassFactory, registration
   ├── KorJpnIme.cpp          ITfTextInputProcessor + ITfDisplayAttributeProvider
   ├── KeyHandler.cpp         ITfKeyEventSink + viterbi-driven candidate build
   ├── HangulComposer.cpp     pure 2-beolsik state machine (testable in isolation)
   ├── Composition.cpp        preedit + commit via TSF edit sessions
   ├── DisplayAttributes.cpp  ITfDisplayAttributeInfo (dotted blue underline)
   ├── BatchimLookup.h        single-syllable Korean → kana with batchim rules
   ├── Dictionary.cpp         legacy text dict reader (mmap'd jpn_dict.txt)
   ├── RichDictionary.cpp     binary dict reader (mmap'd kj_dict.bin)
   ├── Connector.cpp          binary connection cost matrix (mmap'd kj_conn.bin)
   ├── Viterbi.cpp            top-K viterbi over RichDictionary + Connector
   ├── UserDict.cpp           per-user learning dict + LFU pruning
   ├── Settings.cpp           %APPDATA% settings.ini + hot-reload
   ├── CandidateWindow.cpp    Win32 popup, mouse + keyboard navigation
   └── KanaConv.h             hiragana ↔ katakana conversion helpers

tsf/tests/                    minimal header-only test runner (56 cases)
tools/                        install / uninstall / make_release / build_tests
```

## Limitations

- **Reconversion not implemented.**  Selecting an already-committed
  string and asking for a different conversion (the standard
  `ITfFnReconversion` flow) is on the roadmap.  Workaround: delete
  and retype.
- **First install + every update needs a logout/login cycle.**
  ctfmon caches the loaded TIP DLL per session; new DLLs are only
  picked up across a fresh login.  Working as designed for TSF.
- **Tested on Windows 11.**  Should work on Windows 10 (same TSF
  surface) but is not exercised there in CI.
- **UserDict learning influences first-segment ranking only.**
  Viterbi paths themselves don't yet incorporate user-chosen
  surfaces into their cost.  Planned for a future patch.
- **Top-K viterbi is K=5.**  Hard-coded; not yet a settings knob.
- **No tray icon.**  Mode state is visible only via the preedit /
  candidate window.

## License

This project is **MIT-licensed** (see `LICENSE`).

The shipped dictionary data (`jpn_dict.txt`, `kj_dict.bin`,
`kj_conn.bin`) is derived from Google Mozc OSS (BSD-3) which itself
derives from NAIST IPAdic and the Okinawa Public-Domain Dictionary.
Full third-party attribution text is in `dict/LICENSES.txt` and MUST
be redistributed alongside the data.

## Contributing

Issues and pull requests welcome.  For significant changes, please
open an issue first to discuss what you'd like to change.

When fixing bugs, the existing test suite (`tools\build_tests.bat`)
should keep passing; add a regression case for the bug you're
fixing whenever it's testable in pure-logic land.
