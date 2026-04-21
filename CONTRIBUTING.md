# Contributing to KorJpnIme

Thanks for taking the time to contribute.  This document covers the
practical bits -- how to set up, where the code lives, how to run
tests, and what to expect in review.

## Quick links

- [Open an issue](https://github.com/ccy5123/kor_based_jap/issues/new/choose)
  -- bug reports and feature requests
- [Existing issues](https://github.com/ccy5123/kor_based_jap/issues)
- [Current release](https://github.com/ccy5123/kor_based_jap/releases/latest)
- [README (English)](README.md) / [한국어](README.ko.md) / [日本語](README.ja.md)

## Ways to help

- **Try v1.0.0 and report bugs.**  The single most useful contribution
  right now is just running the IME on your machine and filing issues
  when something doesn't work -- especially edge cases in
  segmentation, verb conjugation, loanwords, or unusual keyboard
  layouts.
- **Fix a filed issue.**  Look for the `good first issue` label.
- **Extend the mapping table.**  `mapping/syllables.yaml` drives
  Korean-syllable → kana conversion.  Additions go through
  `tsf/tools/gen_table.py` which regenerates the compiled header.
- **Improve the viterbi engine.**  Top-K is K=5 today; a `settings.ini`
  knob or UserDict integration into path cost would be welcome.
- **Implement Reconversion (ITfFnReconversion).**  One of the only
  standard TSF functions we don't implement yet; see the "known
  limitations" in the README for context.

## Dev setup

You need:

- Windows 10/11
- Visual Studio 2022 Build Tools (MSVC v143 + Windows 10 SDK)
- Python 3.10+ (only for regenerating the binary dictionary data)
- Git bash or WSL (the fetch script is bash; everything else is cmd /
  PowerShell)

First-time bootstrap:

```cmd
:: One-time: pull Mozc OSS data sources (~95 MB of TSV, gitignored)
cd dict\mozc_src
fetch.sh           :: bash / WSL / Git bash

:: One-time: build the binary engine inputs (~71 MB gitignored output)
cd ..
python build_viterbi_data.py

:: Then, iteratively: rebuild DLL + bundle release folder
cd ..
tools\make_release.bat
```

## Running tests

```cmd
tools\build_tests.bat
```

Runs the 56-case unit suite (HangulComposer state machine, batchim
suffix rules, viterbi smoke).  Exit code 0 iff every case passed.

Every PR that touches `tsf/src/HangulComposer.cpp`,
`tsf/src/BatchimLookup.h`, `tsf/src/Viterbi.cpp`, or the generated
tables should keep the suite green and add a regression case when
fixing a bug.

## Code style

- **C++23 / C++20** with MSVC.  C++ rules live in
  `.claude/rules/moai/languages/cpp.md` but in practice: smart
  pointers over raw new/delete, RAII for Windows handles, no
  `using namespace` in headers, `static_cast` over C-style casts.
- **4-space indentation, LF line endings** inside source files.
  Release scripts are `.bat` with CRLF -- leave those alone.
- **English comments in source.**  User-facing docs (README, release
  notes, CHANGELOG) are multi-lingual; code comments are English for
  consistency across contributors.
- **Descriptive commit messages.**  First line short (<70 chars),
  body explains WHY the change was needed if it's not obvious from
  the diff.  Conventional-commits-ish (`tsf: ...`, `dict: ...`,
  `docs: ...`, `chore: ...`) is preferred but not enforced.

## @MX annotations

The codebase uses `@MX:NOTE` / `@MX:WARN` / `@MX:ANCHOR` /
`@MX:TODO` comment tags for AI-agent-readable context.  If you
touch a function that has an `@MX:ANCHOR` tag, keep the contract it
documents -- or update the tag when the contract genuinely changes.
Not a blocker, just a note.

## PR checklist

Before opening a PR:

- [ ] `tools\build_tests.bat` passes.
- [ ] `tools\make_release.bat` produces a DLL that loads (regsvr32,
      no COM errors in Event Viewer).
- [ ] If user-visible, updated `README.md` / `README.ko.md` /
      `README.ja.md` consistently.
- [ ] If behaviour-changing, added a CHANGELOG entry under
      `## [Unreleased]` (create the section if missing).
- [ ] Commits are squash-friendly -- no merge commits from main.

## Code of conduct

Be respectful.  This is a small, hobby-scale project; let's keep
review friendly and focused on the code.

## Reporting security issues

If you find a security issue (e.g., a crash in ctfmon that breaks
system-wide keyboard input), please **do NOT open a public issue**.
Instead, email the maintainer directly (see `git log` for the
committer address) with details, and give them a reasonable window
to fix before disclosing publicly.
