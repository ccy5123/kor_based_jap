# Google Play Store 출시 가이드

> v1.0.0-android는 GitHub Releases로 사이드로드 배포됨.  Play Store
> 진출을 위해 추가로 해야 할 작업과 현재 상태에서 부족한 점을 정리한
> 체크리스트.  설계 / 라이선스 / 정책 / 빌드 4개 영역으로 나눔.

## 0. 비용 / 기간

- **Google Play Console 등록비**: 1회 $25 (USD).  결제 후 영구.
- **신원 확인**: 정부 발급 신분증 + 카드 정보.  최근(2023년 이후)에
  정책 강화로 *연락 가능 주소* 필수.  **앱 페이지에 공개되니** 개인정보
  노출 신경 쓸 거면 사업자등록 (1인 기업) 후 사업장 주소 쓰는 게 무난.
- **심사 기간**: 최초 등록 시 7–14일 (신규 계정은 14일 테스트 의무 +
  20명 이상 테스터 필요).  정책 강화 이후 **이게 가장 큰 진입장벽**.

## 1. 라이선스 / IP — 가장 먼저

### 1.1 Mozc 라이선스 attribution (필수)

엔진(Viterbi) + 사전(`kj_dict.bin`, `kj_conn.bin`)이 Mozc 기반.
**Apache 2.0 / BSD 3-clause** 라이선스 의무 사항:

- [ ] `assets/`에 원본 LICENSE / NOTICE 파일 포함 확인.
  현재 repo에 없으면 [google/mozc 의 LICENSE](https://github.com/google/mozc/blob/master/LICENSE)
  + [data/dictionary 라이선스 NOTICE](https://github.com/google/mozc/blob/master/src/data/dictionary_oss/README.txt)
  복사해서 `android/app/src/main/assets/licenses/MOZC_LICENSE.txt`로 추가.
- [ ] 앱 내 **오픈소스 라이선스 화면** 추가 (Settings → "오픈소스 라이선스").
  AndroidX `androidx.compose.material3:material3` 등 Compose 의존성도 같이.
  - 추천: Google 공식 [oss-licenses-plugin](https://developers.google.com/android/guides/opensource)
    플러그인 → `dependencies` 자동 스캔해서 라이선스 화면 자동 생성.
  - 또는 수동: `assets/licenses/`에 모은 텍스트 파일들을 Compose 화면에서 표시.

### 1.2 한글-기반 일본어 입력 특허 — 만료됨

조사 결과 (2026-04-28):

- **KR100599873B1** (개인 김상근, "한글자모를 이용한 다문자 입력장치"):
  2009-07-06 만료
- **KR100517970B1** (LG전자, "이동통신 단말기의 일본어 입력 장치 및 방법"):
  2017-09-23 만료

핵심 개념("한글 jamo로 일본어 가나 출력")은 한국 기준 public domain.
기록 차원에서 위 2건 만료 사실을 commit message나 별도 doc에 남겨두면
나중에 누가 이의제기해도 근거 됨.

### 1.3 천지인 자판 — 별도 검토 필요 (선택)

ITU-T E.161에 등재된 표준이긴 한데, 삼성 보유 특허가 만료됐는지는
KIPRIS에서 직접 확인 후 결정.  *불안하면 천지인 빼고 두벌식만으로
초기 출시* 도 옵션 — 두벌식은 표준 레이아웃이라 IP 이슈 0.

## 2. 앱 변경 사항 — Play 정책 대응

### 2.1 Privacy Policy URL (필수, IME는 더더욱)

Play Console에 **공개 접근 가능한** URL 입력 필수.  IME는 사용자
키 입력을 가로채는 특성상 정책 심사가 까다로움.  필수 항목:

- 앱이 수집하는 데이터: 우리 앱은 *없음* (모든 처리가 온디바이스).
- UserDict는 SharedPreferences에 *로컬 저장* — 외부 전송 없음.
- 광고 / 분석 SDK *없음*.

가장 쉬운 호스팅: **GitHub Pages**.  `docs/privacy-policy.md` 작성
→ repo 설정에서 Pages 활성화 → `https://ccy5123.github.io/kor_based_jap/privacy-policy.html`
같은 URL 생성.

샘플 정책 (한/영 둘 다):

```markdown
# Privacy Policy — Korean→Japanese IME

This input method **does not collect, store, or transmit any user
data to remote servers**.  All processing happens on the device.

- Typed text is processed locally for Hangul→kana conversion and
  kanji candidate lookup.  Nothing is sent off-device.
- User dictionary (UserDict) entries — kana run + kanji pick history
  — are stored in the app's private SharedPreferences and never
  leave the device.
- The app requests no network permissions.
- No analytics or ads SDKs are bundled.

Contact: [your email]
```

### 2.2 App Bundle (.aab) — APK 대체

Play Store는 **App Bundle 필수** (2021년 8월 이후 신규 앱).  현재
`assembleRelease`로 만든 95 MB APK는 사이드로드용; Play 제출은
`bundleRelease`로:

```kotlin
// android/app/build.gradle.kts — 이미 다 갖춰져 있음, 추가 작업 없음
// Just run:
//   ./gradlew :app:bundleRelease
// 출력: android/app/build/outputs/bundle/release/app-release.aab
```

Bundle은 사용자 기기 ABI에만 맞춘 split APK를 Play가 자동 생성 →
실제 다운로드 크기는 95 MB → ~30–40 MB로 감소 (사전 데이터 제외하면).

### 2.3 Play App Signing (강력 추천)

현재 keystore 우리가 들고 있는 구조 → **Play App Signing** 켜면
Google이 "app signing key"를 보관하고, 우리는 "upload key"만 들고 다님.
- 장점: keystore 분실해도 Google에서 키 reset 가능.
- 흐름: Play Console에서 신청 → 현재 `kor_jp_ime.jks`를 *upload key*로
  업로드 → 신규 *app signing key* 생성 → 이후 모든 업데이트는 upload
  key로 서명.

### 2.4 targetSdk 정책

Play는 매년 *최신 SDK - 1*을 강제.  현재 `compileSdk=35 / targetSdk=35`.
- 2026년 정책: targetSdk ≥ 35 (Android 15) 필요 — **이미 충족**.
- 2027년부터는 36으로 올려야 할 수 있음.

### 2.5 Content rating

Play Console 설문 → IME는 보통 **"Everyone"** 등급.  사전이 비속어 포함
가능성 있는데 (Mozc dict), 사용자가 의도적으로 입력했을 때만 노출되니
실제 등급에 영향 거의 없음.

### 2.6 Permissions

현재 `AndroidManifest.xml` 상태 확인:

```bash
grep uses-permission android/app/src/main/AndroidManifest.xml
```

IME에 필요한 것: **`BIND_INPUT_METHOD` 만**.  네트워크 / 위치 / 카메라
같은 권한은 *없는* 상태 유지 — Play 정책상 IME가 추가 권한 요청하면
즉시 거절 사유.

## 3. Store Listing 자료 준비

### 3.1 텍스트

- **앱 이름** (50자 이내): "한일 IME" / "Korean→Japanese Keyboard" 등
- **간단한 설명** (80자): 한글 자판으로 일본어를 입력하는 IME.
- **자세한 설명** (4000자): README.md 내용 다듬어서.  한국어 / 영어 / 일본어
  3개 언어 등록하면 검색 도달 ↑.
- **카테고리**: Productivity → Input methods.

### 3.2 이미지

- **앱 아이콘**: 512×512 PNG, 모서리 라운드 ❌ (Play가 자동).
- **피처 그래픽**: 1024×500 PNG, Play 페이지 상단 배너.
- **스크린샷**: 폰 (1080×1920 권장) 최소 2장, 최대 8장.
  - 추천: (1) 두벌식 letters, (2) 천지인 letters, (3) 후보 strip 보이는 입력 중,
    (4) Settings 메인, (5) Material You 테마, (6) 다크 모드.
- **태블릿 스크린샷**: 태블릿 지원 안 한다고 명시하면 생략 가능.

### 3.3 출시 노트 (Whats new, 500자)

[`docs/RELEASE_NOTES_v1.0.0.ko.md`](RELEASE_NOTES_v1.0.0.ko.md) 의
요점 추려서 4–5줄.

## 4. 등록 흐름 (Console 단계)

1. [play.google.com/console](https://play.google.com/console) → 개발자
   계정 생성 ($25 결제, 신원 확인 1–2일).
2. **Create app** → 이름 / 언어 / 무료(Free) / IME 카테고리.
3. **Internal testing** 트랙 먼저 → 본인 / 지인 1–2명 추가, AAB 업로드.
4. **Closed testing** 트랙 → 14일간 20명 이상 활성 테스터 필요 (정책상).
   - GitHub Issues에서 "베타 테스터 모집" 공지 + 이메일로 등록받는 방식이
     일반적.
5. **Production** 트랙 → 위 14일 통과 후 신청 가능. 심사 7–14일.

## 5. 사전 점검 체크리스트

출시 전 한 번에 확인:

- [ ] `bundleRelease` 빌드 성공 + APK 사이즈 확인
- [ ] 디버그 로그 (Log.d 등) 제거 — 또는 ProGuard로 stripping (현재
  `isMinifyEnabled = false`라 그대로 들어감.  켜면 사이즈 감소 + Mozc
  바이너리도 그대로니 약간만 감소)
- [ ] AndroidManifest의 permissions 검토
- [ ] Privacy Policy URL 호스팅 (GitHub Pages)
- [ ] 앱 내 오픈소스 라이선스 화면 추가
- [ ] `assets/licenses/MOZC_LICENSE.txt` 추가
- [ ] Store listing 이미지 6장 + 피처 그래픽 + 아이콘 준비
- [ ] 한국어 / 영어 / 일본어 store description 작성

## 6. 부가 — 출시 후

- **버전 관리**: `versionCode` 매 릴리스마다 +1 (Play는 단조증가 강제).
  현재 100 → 다음 101 / 102 ...
- **app:bundleRelease** 자동화: GitHub Actions에서 PR 머지 후 자동
  bundleRelease + 아티팩트 업로드 가능.  단, keystore + 비번을 GitHub
  Secrets에 넣어야 함 (Play App Signing 쓰면 upload key만 노출).
- **사용자 피드백**: Play Console의 리뷰 → 영어/일본어 리뷰가 들어올 수
  있으니 자동 알림 켜놓고 답변.

## 7. 결론

지금 v1.0.0-android는 *기능적으로는* Play Store 제출 가능.  남은 작업:

1. **라이선스 / privacy policy** — 1–2시간 (1.1, 2.1 항목)
2. **Store listing 자료** (스크린샷 / 설명) — 2–3시간
3. **Play Console 등록 + 결제** — 1일
4. **Internal → Closed testing 14일** — 정책상 wait time
5. **심사** — 7–14일

총 2–3주 소요 예상.  서두를 거 없으면 1.1 / 2.1 만 처리해서 GitHub
Release에 라이선스 / privacy policy 추가해놓고 천천히 진행해도 OK.
