---
title: Privacy Policy
permalink: /privacy-policy.html
---

# Privacy Policy — Korean → Japanese IME (kor_based_jap)

_Last updated: 2026-04-28_

This Input Method Editor (IME) for Android is published by an
individual developer as an open-source project.  We respect your
privacy and have designed the app to keep all input data on your
device.

## Summary in one sentence

**This IME does not collect, transmit, or share any user data with
remote servers — every keystroke, conversion, and personalisation
detail stays on your device.**

## What the app does with your input

When you use the IME to type, the following processing happens
**entirely on your device**:

- Korean Hangul jamo input is composed into syllables locally
  (HangulComposer).
- The composed Hangul is converted to Japanese kana using a
  bundled lookup table (BatchimLookup).
- The kana run is matched against an offline kanji dictionary
  (`jpn_dict.txt`) and a Viterbi engine (`kj_dict.bin` + `kj_conn.bin`)
  to surface candidate kanji conversions in the candidate strip.
- When you pick a candidate, the (kana, kanji) pair is written to a
  per-user dictionary in the app's private storage so that future
  matches surface your previous picks first.

None of the above involves a network request.  The app declares no
`INTERNET` permission in its manifest, so it is technically incapable
of making outbound network calls.

## Data we do NOT collect

- Keystrokes typed in any app.
- Text contents of any input field.
- Identifiers (advertising ID, device ID, IMEI, IP address, etc.).
- Location, camera, microphone, contacts, photos, or any other
  device data.
- Crash reports or analytics — the app bundles **no** analytics or
  crash-reporting SDK.

## Data stored on the device

The app uses standard Android data stores for **local-only**
preferences and personalisation:

- **DataStore Preferences**: keyboard layout (두벌식 / 천지인),
  theme direction, dark mode, haptic feedback toggle, keyboard height,
  candidate count cap, input language (한 / 영 / 일).
- **SharedPreferences (UserDict)**: per-kana run kanji pick history.
  Used to rank candidates so frequently-picked kanji come first.

Both stores live in the app's private sandbox (`/data/data/<package>/`).
Other apps cannot read them.  Uninstalling the app deletes them.

## Third-party data and licenses

The bundled dictionary data is derived from Google Mozc's open-source
dictionary, which itself derives from NAIST IPAdic and the Okinawa
Public-Domain Dictionary.  Full attribution and license text is
included with the app at `assets/licenses/THIRD_PARTY_LICENSES.txt`
and is available in the source repository.

The app has no third-party SDKs at runtime.  Build-time dependencies
(AndroidX, Jetpack Compose, Material 3, kotlinx.coroutines) ship as
library code and do not communicate externally.

## Permissions

The app declares only `BIND_INPUT_METHOD` (required for any Android
IME).  It declares **no** other permissions — no internet, no
location, no contacts, no storage.

## Children

The app is suitable for general audiences and does not knowingly
collect any data from anyone, including children under 13.

## Changes to this policy

If this policy ever changes (for example, if a future version adds
a feature that requires a network call — currently not planned),
the change will be:

1. announced in the GitHub release notes for the affected version;
2. reflected in this file with the "Last updated" date at the top
   bumped accordingly.

We will not retroactively change the data-handling behaviour of an
already-installed version.

## Contact

Source code and issues:
[github.com/ccy5123/kor_based_jap](https://github.com/ccy5123/kor_based_jap)

Email: ccy5123ccy [at] gmail [dot] com

Open a GitHub issue for bug reports or feature requests; use email
for private privacy-related questions.
