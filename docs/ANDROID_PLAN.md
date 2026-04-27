# Android port plan

> Living planning doc.  Carried across sessions; update as decisions
> firm up or get reversed.  Created at v1.0.0 (Windows TSF) shipping
> point as the bridge into the Android sub-project.

## Status

- 🟢 **Windows TSF v1.0.0 shipped.**  See [CHANGELOG](../CHANGELOG.md).
- 🟡 **Android port: planning.**  No code yet.  Next milestone: M1 below.

## Agreed direction

These are the architecture / scope decisions settled before any
Android code lands.  Reverse them in this file (with the reason)
rather than silently deviating in code.

| Decision | Choice | Why |
|---|---|---|
| Engine code strategy | **Pure Kotlin port** (no NDK) | HangulComposer + BatchimLookup + Viterbi together are ~700 LOC of pure logic; porting beats wiring up JNI + per-ABI builds (arm64 / armv7 / x86 / x86_64) |
| UI framework | **Jetpack Compose** | Declarative, modern Android standard; Compose Preview makes iteration fast |
| Repo layout | **Same repo, `android/` subfolder** | Single source of truth for shared assets (`dict/`, `mapping/`); Windows + Android release tags can diverge |
| Dictionary distribution | **Bundle in APK** for v1 (jpn_dict.txt, ~18 MB) | Zero-network install, modest APK growth.  Defer kj_*.bin (~71 MB) until users ask |
| Distribution channel | **GitHub Releases (signed APK)** for beta → Google Play after stabilization | Iterate fast without Play store review; Play later for discoverability |
| Input methods | **2-beolsik (두벌식) + Cheonjiin (천지인)** | Two-beolsik covers desktop-typist users (matches the Windows TSF version); Cheonjiin is the default mobile Korean input on Samsung phones — Note20-class users would expect it.  User picks one of the two layouts in **Settings → 입력 방식 → 한글 키보드 레이아웃**; the keyboard surface itself shows no mode toggle (decision from 2026-04-27 Claude Design session — "most users settle on one layout for life; on-keyboard toggle wastes a slot, adds noise, risks accidental swaps mid-message"). Selection persisted in DataStore |

## Milestones

### M1 -- "It works as an IME, hiragana only"

Smallest shippable thing: install APK, switch to it as keyboard,
type Korean jamo, see hiragana committed.  No kanji conversion yet.

- [ ] Android Studio project skeleton under `android/`
- [ ] AndroidManifest registers an `InputMethodService`
- [ ] Compose-based **2-beolsik** keyboard view (32 jamo keys + Shift /
      Backspace / Space / Enter / function row with !#1 / globe /
      `,` / `.`)
- [ ] Compose-based **천지인 (Cheonjiin)** keyboard view (3×4 grid:
      9 consonant-group keys + ㅣ ㆍ ㅡ vowel column + Backspace /
      Space / Enter / globe)
- [ ] **Cheonjiin state machine** (NEW — no Windows ancestor):
      multi-tap consonant cycling (ㄱ→ㅋ→ㄲ etc.), vowel composition
      (ㅣ + ㆍ → ㅏ etc.), tap-timeout reset.  Output: jamo stream
      that feeds the same HangulComposer downstream
- [ ] **Settings screen** (Compose) — 입력 방식 page with 한글 키보드
      레이아웃 picker: two ModeCards (두벌식 / 천지인) with mini-preview
      and radio selection, plus Japanese candidates / Theme / Haptics
      rows.  This is the *only* place the user switches modes
- [ ] User mode preference persisted via DataStore (default 두벌식;
      Settings card flips it)
- [ ] Port `tsf/src/HangulComposer.cpp` (~400 LOC) to Kotlin verbatim
      — shared by both modes
- [ ] Port `tsf/src/BatchimLookup.h` (~200 LOC) to Kotlin
- [ ] Port the syllable-table generator output to a Kotlin `Map`
      (or load `mapping/syllables.yaml` at build time)
- [ ] On every committed Korean syllable, look up the kana and
      `InputConnection.commitText` it
- [x] Sideload + smoke-test in any text field (두벌식 D2-verified
      2026-04-27 on Note20 Ultra; 천지인 still untested in service —
      mode toggle waits for Settings DataStore)

### D2 progress -- "IME runs on the phone, raw jamo out"

Slice of M1 between "keyboard renders in @Preview" and "Hangul
composer wired up".  Goal: confirm the system actually shows our
ComposeView when the user picks KorJpnIme as their keyboard, and
that taps reach the editor.

Landed:

- `keyboard/KeyAction.kt` -- sealed dispatch type
  (Commit / Backspace / Enter / Space / SwitchIme / Shift / Symbols)
- `KorJpnImeService.onCreateInputView()` -- hosts a `ComposeView` with
  Lifecycle / ViewModelStore / SavedStateRegistry owners attached
  before `setContent` (Compose pulls them off the view tree)
- `KeyboardSurface` / `BeolsikLayout` / `CheonjiinLayout` / `Key` /
  `SpaceKey` all take an `onAction: (KeyAction) -> Unit = {}`
  callback (default no-op so the 14 @Preview entries stay inert)
- Symbol / shift keys dispatch but the service no-ops them; cycle of
  multi-tap consonants in Cheonjiin emits *primary* jamo only
  (state machine is D3)
- `androidx.savedstate:savedstate-ktx` added to `libs.versions.toml`
  + `app/build.gradle.kts` (needed for the view-tree extension)
- Mode + direction hard-coded to **두벌식 + d1 Stratus** in
  `KorJpnImeService` -- DataStore wiring is later in M1

D2 explicitly defers:

- Hangul composition (raw `ㅎ` `ㅏ` `ㄴ` instead of `한`) -- D3
- Cheonjiin multi-tap cycling + vowel composition -- D3
- Press-down haptics / key-down feedback (currently fires on tap-up
  via `Modifier.clickable`, fine for first verification)
- Symbols page, shift-locked uppercase
- Settings screen / mode persistence

Verification on Note20 Ultra (Android 13) -- run after a clean
`./gradlew assembleDebug` in Android Studio:

- [x] APK installs (`adb install -r app/build/outputs/apk/debug/app-debug.apk`)
- [x] **Settings → 일반 → 언어 및 입력 → 화면 키보드 → 키보드 관리** lists
      *KorJpnIme* and the toggle stays on
- [x] Pull down notification shade → **키보드 선택** chip → KorJpnIme
- [x] Open a text field -- the d1 Stratus surface paints (after the
      `KorJpnImeView` fix, see post-mortem below)
- [x] Tap a jamo key (e.g. ㅎ) -- the literal jamo appears in the
      editor (not yet composed into a syllable)
- [x] Backspace deletes one code unit
- [x] Space inserts a real space
- [x] Enter inserts newline / performs editor action
- [x] Globe key cycles to the next system IME and back
- [x] No crash during 10+ min of use (verified via logcat —
      `FreecessController` heartbeat steady, no AndroidRuntime errors)
- [ ] Shift / 쌍자음 -- intentionally deferred to D3 (Shift dispatches
      `KeyAction.Shift` but the service no-ops it; ㄲ ㄸ ㅃ ㅆ ㅉ wait
      until the Shift state machine lands)

D2 post-mortem -- the bug we hit on first sideload:

`onCreateInputView()` initially returned a plain `ComposeView` with the
three view-tree owners set on the ComposeView itself.  This crashed
immediately with `IllegalStateException: ViewTreeLifecycleOwner not
found from android.widget.LinearLayout{... android:id/parentPanel}`.

Cause: the IME framework wraps whatever `onCreateInputView()` returns
inside a system-managed `parentPanel`.  Compose's `WindowRecomposer`
walks **up** from rootView to find the lifecycle owner -- anything
attached to the ComposeView itself (which is a *child* of parentPanel)
is invisible from above.

Fix: introduce `KorJpnImeView` extending `AbstractComposeView`, and in
`onAttachedToWindow()` set the three owners on `rootView` *before*
calling `super.onAttachedToWindow()` (which is what kicks off
composition).  This is the same pattern Florisboard / Tipal / other
Compose-based IMEs use.

### M2 -- "Kanji conversion via the simple dict"

- [ ] Bundle `dict/jpn_dict.txt` in `assets/`
- [ ] Port `tsf/src/Dictionary.cpp` (mmap) to Kotlin (`RandomAccessFile`
      + `FileChannel.map` for memory mapping)
- [ ] Candidate strip Compose component above the keyboard (horizontal
      scrolling row, shows kanji candidates after Space)
- [ ] Port `tsf/src/UserDict.cpp` -- back it with Room or
      `SharedPreferences` (Android-native persistence instead of a
      flat text file)
- [ ] Auto-katakana fallback (port the same logic from KeyHandler.cpp)

### M3 -- "Viterbi engine (Mozc parity)"

- [ ] Bundle `kj_dict.bin` + `kj_conn.bin` in `assets/` (~71 MB total
      -- check Play APK size limits + use App Bundle splits if
      needed)
- [ ] Port `RichDictionary` / `Connector` / `Viterbi` to Kotlin
- [ ] Top-K integration (K=5) into the candidate strip

### M4 -- "Polish + release"

- [ ] Settings activity (Compose) -- katakana toggle, theme,
      candidate-count knob
- [ ] Material 3 dynamic color (Material You)
- [ ] Light / dark theme support
- [ ] Settings translation (한 / 日 / 영) parallel to existing READMEs
- [ ] APK signing setup
- [ ] First GitHub Release tag like `v1.0.0-android` (kept separate
      from the Windows release version line)

## Files to port (Windows -> Kotlin)

Direct 1:1 ports, with file size for sizing each task:

| Windows file | LOC | Notes |
|---|---:|---|
| `tsf/src/HangulComposer.{h,cpp}` | ~400 | Pure state machine, easy port |
| `tsf/src/BatchimLookup.h` | ~200 | Header-only, includes generated tables |
| `tsf/generated/mapping_table.h` | gen | Convert YAML -> Kotlin `Map` |
| `tsf/generated/batchim_rules.h` | gen | Convert YAML -> Kotlin sealed class |
| `tsf/src/KanaConv.h` | ~30 | Trivial unicode-shift helpers |
| `tsf/src/Dictionary.{h,cpp}` | ~150 | mmap'd text reader -> Kotlin equivalent |
| `tsf/src/UserDict.{h,cpp}` | ~150 | Persisted map -> use Room |
| `tsf/src/Settings.{h,cpp}` | ~200 | INI -> Android `SharedPreferences` or DataStore |
| `tsf/src/RichDictionary.{h,cpp}` | ~200 | mmap binary -> `MappedByteBuffer` |
| `tsf/src/Connector.{h,cpp}` | ~100 | mmap binary -> `MappedByteBuffer` |
| `tsf/src/Viterbi.{h,cpp}` | ~250 | Top-K viterbi DP, pure logic |

Things that don't port (Windows-specific):
- COM / TSF interfaces (replace with `InputMethodService`)
- `ITfDisplayAttributeProvider` (Compose handles preedit styling)
- `regsvr32` / `install_tip.reg` (replace with APK install)

## Files to author from scratch (no Windows ancestor)

Android-only additions, not derived from any TSF source file:

| File | LOC est. | Notes |
|---|---:|---|
| `CheonjiinStateMachine.kt` | ~200 | Multi-tap consonant cycling + vowel composition (ㅣ/ㆍ/ㅡ → all vowels); tap-timeout reset; outputs the same jamo stream HangulComposer expects |
| `CheonjiinLayout.kt` | ~100 | 3×4 Compose grid; large keys for one-handed thumb reach |
| `BeolsikLayout.kt` | ~150 | 4×11 Compose grid for the standard 두벌식 layout |
| `KeyboardModePreference.kt` | ~50 | DataStore-backed `KeyboardMode` enum (BEOLSIK / CHEONJIIN); observes mode flow |
| `SettingsScreen.kt` | ~250 | Settings activity Compose screen with mode picker (two ModeCards w/ mini-preview), Japanese-candidates row, theme row, haptics toggle |
| `OklchColor.kt` | ~50 | Single-hue OKLCH → sRGB conversion for the design's parametric token system |
| `KeyboardTheme.kt` | ~150 | `DirectionPalette` + `tokens(palette, dark)` returning 11 colour tokens (ports the Claude Design `tokens()` function) |

## UI design notes

- **Reference IMEs**: Gboard (Korean / Japanese, both 두벌식 and
  12-key 천지인 modes), Samsung Keyboard (천지인 default on Note-class
  phones), SwiftKey, Google JP IME on Android, Mozc Android.  Look at
  key sizing, candidate strip placement, theme switch UX, and how the
  두벌식 ↔ 천지인 mode toggle is surfaced.
- **Touch target**: 48dp minimum (Material Accessibility); keys can
  be larger.  Keep gutters wide enough for thumbs.
- **Layout**: portrait + landscape both.  Landscape can show a wider
  candidate strip or split keyboard.
- **Themes**: light / dark / system; Material You dynamic color is a
  free win in Compose.
- **Claude Design** (Anthropic Labs research preview, launched
  2026-04) can generate visual mockups from natural-language briefs;
  useful for color / layout iteration before writing Compose code.

## New-session bootstrap

Open Claude Code at `C:\dev\kor_based_jap` (Windows native — that's
where Android Studio reads from; the WSL worktree is no longer the
source of truth), then paste:

```
이전 세션에서 Android M1 의 키보드 UI 까지 완성했어.
repo: https://github.com/ccy5123/kor_based_jap (작업 경로 C:\dev\kor_based_jap)
폰: Galaxy Note20 Ultra 5G, Android 13
픽한 design direction: d1 Stratus (cool blue, rounded square, chip strip)
ANDROID_PLAN.md 의 M1 체크리스트에서 키보드 view + 14개 @Preview 까지 ✓.

다음으로 D2 — IME 가 실제로 폰에서 동작하게:
- KorJpnImeService.onCreateInputView() 에 ComposeView 호스팅
  (lifecycle / SavedStateRegistry / ViewModelStore owner 셋업 포함)
- 키 탭 → InputConnection.commitText (raw 자모 출력만; 한글 합성은 D3)
- Note20 Ultra 에 sideload + 메모장에서 입력 검증
```
