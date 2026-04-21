# Changelog

All notable changes to KorJpnIme are documented in this file.  Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] -- 2026-04-21

First release.  Functionally complete Korean-keyboard Japanese IME
with Mozc-quality kanji conversion.

### Added -- input pipeline

- Korean 2-beolsik layout → Japanese kana, including all standard
  compound vowels (ㅗ+ㅏ=ㅘ → わ, ㅜ+ㅓ=ㅝ → を-equivalent katakana,
  …) and compound jong with proper migration on subsequent vowel
  (ㄴ+ㅈ=ㄵ → ㄴ stays as final-ん, ㅈ migrates to next syllable).
- Hatsuon ん from final ㄴ/ㅁ/ㅇ.  Sokuon っ from ㅎ (always), ㅅ/ㅆ
  (terminal or before consonant), and the ㄱ/ㅂ/ㄷ/ㅅ "strict"
  family (ㄱ jong + ㄱ/ㅋ cho → っ, etc.).
- Three Japanese-particle keystroke patterns repurposed from
  otherwise-meaningless Korean sequences:
  - `ㅇ-ㅗ-ㅗ` → を
  - `ㅇ-ㅘ-ㅏ` → は
  - `ㅇ-ㅔ-ㅔ` → へ

  Real long-vowel forms (おお, ええ) preserved -- they require an
  explicit ㅇ between vowels.

### Added -- conversion engine

- Full Mozc OSS dictionary integration.  ~750k unique kana keys,
  ~1.29 M dictionary entries with per-entry POS ID (lid/rid) and
  Mozc cost.
- Binary engine inputs (`kj_dict.bin` ~57 MB, `kj_conn.bin` ~14 MB)
  generated from upstream Mozc TSV via `dict/build_viterbi_data.py`.
  Mmap-loaded for negligible RAM footprint.
- 1-best Viterbi segmentation over the full Mozc bigram connection
  cost matrix (2672×2672 int16 entries) -- compound forms like
  わたしの correctly decompose into 私 + の and surface as a single
  "私の" candidate that consumes the whole pending buffer.
- Top-K (K=5) Viterbi paths surfaced as alternative joined-surface
  candidates so the user sees multi-segmentation alternatives, not
  just the 1-best pick.
- Verb conjugation support via Mozc's `suffix.txt` (る / た / て /
  ない / ます / ...).  たべます now picks 食べ + ます properly even
  though the full inflected form isn't in the main dictionary.
- Auto-katakana fallback for loanwords -- when the dict can't reach
  kanji, the candidate list still offers ハンバーガー for 한바아가아.
- Per-user learning dictionary (`user_dict.txt`).  Every selected
  kanji bumps a count; later lookups bubble user-favoured surfaces
  to the top.  LFU eviction at save time keeps the file under
  ~5000 entries by default (configurable, 0 = unlimited).

### Added -- UI / TSF integration

- Custom display attribute (`ITfDisplayAttributeProvider`) renders
  the preedit with a dotted blue underline matching MS-IME / Mozc
  visual style.
- Caret-aware candidate window via `ITfContextView::GetTextExt`.
  Pops up next to the cursor, flips above when near the bottom of
  the work area, multi-monitor aware.
- Mouse + keyboard navigation in the candidate window: number keys
  1–9 for direct selection on the visible page, arrow keys for
  inter-page nav, Tab to expand to a long scrollable list, mouse
  click + hover.
- Per-jamo backspace -- undo simplifies compound jong (ㄳ → ㄱ),
  compound vowel (ㅘ → ㅗ), then jong, then jung, then cho.

### Added -- configuration

- `%APPDATA%\KorJpnIme\settings.ini` auto-created on first run with
  documented defaults.
- Hot-reload via `FindFirstChangeNotificationW` -- save the INI and
  the next keystroke applies the change.  No log-out required.
- Settings keys: `KatakanaToggle` (hotkey), `FullWidthAscii`,
  `UserDictLearn`, `UserDictMaxEntries`.

### Added -- packaging

- `install.ps1` / `uninstall.ps1` self-elevating scripts.  Handle
  COM registration, TSF profile import, and clean removal.
- `tools/make_release.bat` produces a redistributable folder ready
  to zip and ship.
- `tools/build_tests.bat` runs the 56-case unit suite.

### Compatibility

- Windows 11 (primary target).
- Windows 10 should work (same TSF surface) but is not exercised in
  CI.
- Built with MSVC (Visual Studio 2022 Build Tools).  The MinGW
  build path was retired -- ctfmon's MSVC ABI assumptions caused
  vtable / heap mismatches.

### Known limitations

- Reconversion (`ITfFnReconversion`) not implemented.  Tracked for
  a future release.
- UserDict learning currently influences first-segment ranking
  only; viterbi path costs themselves do not yet incorporate user
  preference.
- Top-K count is hard-coded at 5; not yet exposed in `settings.ini`.
- No system-tray icon.  Mode state is visible only through the
  preedit and candidate window.
