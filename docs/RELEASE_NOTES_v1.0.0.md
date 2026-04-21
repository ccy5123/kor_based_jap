# KorJpnIme v1.0.0

First public release -- a Windows TSF IME that lets you type Japanese on
a Korean 2-beolsik keyboard layout with Mozc-quality kanji conversion.

## Install

1. Download `KorJpnIme-v1.0.0.zip` (attached).
2. Unzip anywhere.
3. From an admin PowerShell:
   ```powershell
   powershell -ExecutionPolicy Bypass -File install.ps1
   ```
4. **Log out and log back in.**
5. Settings -> Time & Language -> Language -> Korean -> Language options
   -> Add a keyboard -> *Korean-Japanese IME*.
6. Press `Win+Space` to switch to it whenever you want to type Japanese.

See the bundled `README.md` and `CHANGELOG.md` for details.

## What's in the box

- **Korean 2-beolsik -> Japanese kana** including compound vowels, compound
  jong, hatsuon/sokuon detection.
- **Mozc OSS dictionary** (~750k kana keys, ~1.29 M entries) with
  per-entry cost + lid/rid.
- **Top-K Viterbi segmentation** over Mozc's 2672x2672 bigram cost
  matrix -- compound forms like わたしの decompose into 私+の with
  the joined surface surfaced as a single candidate.
- **Verb conjugation** via Mozc's suffix table (る / た / ます / ない /
  ...) -- たべます, よみました, きれいです all segment correctly.
- **Auto-katakana fallback** for loanwords (ハンバーガー for 한바아가아).
- **Three particle-marker keystroke patterns** repurposed from
  otherwise-meaningless Korean sequences:
  - `ㅇ-ㅗ-ㅗ` -> を
  - `ㅇ-ㅘ-ㅏ` -> は
  - `ㅇ-ㅔ-ㅔ` -> へ
- **Per-user learning dictionary** with LFU pruning (5000 entry cap by
  default, knob in settings.ini).
- **Hot-reloadable settings** at `%APPDATA%\KorJpnIme\settings.ini`.
- **MS-IME / Mozc-style preedit** (dotted blue underline).

## Known limitations

- Reconversion (`ITfFnReconversion`) not implemented -- delete and
  retype to get a different conversion.
- First install + every update needs a logout/login cycle (ctfmon
  caches the loaded TIP DLL per session).
- UserDict learning currently influences first-segment ranking only;
  viterbi path costs don't yet incorporate user preference.
- Top-K count is hard-coded at 5; not yet in settings.ini.
- Tested primarily on Windows 11.  Windows 10 should work but is not
  exercised in CI.

## Bundled third-party data

`jpn_dict.txt` / `kj_dict.bin` / `kj_conn.bin` are derived from Google
Mozc OSS (BSD-3) which itself derives from NAIST IPAdic and the
Okinawa Public-Domain Dictionary.  See `dict/LICENSES.txt` (bundled)
for full attribution.

## MD5 sums

Run `Get-FileHash KorJpnIme-v1.0.0.zip -Algorithm MD5` after download
to verify -- official hash will be posted to the release page once the
upload completes.
