# KorJpnIme — 한국어 키보드로 일본어 입력

[![Release](https://img.shields.io/github/v/release/ccy5123/kor_based_jap?display_name=tag&sort=semver)](https://github.com/ccy5123/kor_based_jap/releases/latest)
[![License](https://img.shields.io/github/license/ccy5123/kor_based_jap)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/ccy5123/kor_based_jap/tests.yml?branch=main&label=tests)](https://github.com/ccy5123/kor_based_jap/actions/workflows/tests.yml)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-blue)](https://github.com/ccy5123/kor_based_jap/releases/latest)
[![Stars](https://img.shields.io/github/stars/ccy5123/kor_based_jap?style=social)](https://github.com/ccy5123/kor_based_jap/stargazers)

**한국어 2벌식 키보드 레이아웃**으로 일본어를 입력할 수 있는 Windows TSF
(Text Services Framework) 입력기입니다. 한국어 음절 (와타시노, 갓코우,
한바아가아…) 을 치면 KorJpnIme가 일본어 카나로 변환하고, Mozc 수준의
viterbi 기반 분절 엔진으로 한자 후보를 제공합니다.

> 🌏 Languages: [English](README.md) · **한국어** · [日本語](README.ja.md)

```
와타시   +  Space   →   私 / 渡し / 渡 / ワタシ / わたし
와타시노 +  Space   →   私の / 渡しの / わたしの / ワタシノ
갓코우   +  Space   →   学校 / 月光 / がっこう / ガッコウ
ㅇㅗㅗ   (Space 없음) →   を       (목적격 조사)
ㅇㅘㅏ   (Space 없음) →   は       (주제 조사)
ㅇㅔㅔ   (Space 없음) →   へ       (방향 조사)
한바아가아 + Space  →   ハンバーガー  (외래어 자동 카타카나)
```

모드 토글도 없고, 로마자 중간 단계도 없고, 외워야 할 별도 단축키도 없습니다.
다른 일본어 IME처럼 커서 옆에 후보창이 뜹니다.

---

## 주요 기능

- **한국어 2벌식 → 일본어 카나**: 복합 모음 (ㅗ+ㅏ=ㅘ → わ), 복합 종성
  (ㄴ+ㅈ=ㄵ → 분리 마이그레이션) 완전 지원
- **하츠온 ん / 소쿠온 っ 자동 감지**: ㄴㅁㅇ → ん, ㅎ → っ, ㅅㅆ는 문장
  끝이나 자음 앞에서 → っ 등
- **Mozc OSS 사전**: 약 75만 개 고유 kana 키, 약 129만 엔트리, Mozc
  원본 비용값 포함
- **Viterbi top-K 분절**: Mozc의 bigram 연결 비용 행렬 기반. わたしの
  같은 복합형이 `私の`, `渡しの` 등으로 분절된 후보로 상단에 노출
- **동사 활용 지원**: Mozc suffix 테이블 통합으로 `たべます`,
  `よみました`, `きれいです` 등이 원형이 사전에 없어도 정확히 분절
- **외래어 자동 카타카나 대체**: 한자 경로가 없는 경우에도 `한바아가아`
  같은 입력에 대해 `ハンバーガー` 후보 제공
- **일본어 조사 3종 특수 키 패턴**: 한국어에서 의미 없는 시퀀스 재활용:
  - `ㅇ-ㅗ-ㅗ` → を
  - `ㅇ-ㅘ-ㅏ` → は
  - `ㅇ-ㅔ-ㅔ` → へ

  실제 장음 (おお, ええ) 은 영향 없음 — 모음 사이에 명시적 ㅇ 이 필요하기 때문
- **사용자 학습 사전**: 선택한 한자가 다음번에 상위로 이동. 기본 5000 엔트리
  상한 (LFU 정리), `settings.ini`에서 조정
- **설정 핫리로드**: `%APPDATA%\KorJpnIme\settings.ini` 편집 시 다음 키
  입력에서 즉시 반영. 로그아웃 불필요
- **카타카나 모드 토글** (설정 가능, 기본 `RAlt+K`)
- **커스텀 디스플레이 속성**: preedit 텍스트를 MS-IME / Mozc 스타일
  점선 파란 밑줄로 렌더링

## 빠른 시작

### 설치

1. GitHub release 페이지에서 최신 `KorJpnIme-vX.Y.Z.zip` 다운로드
   (또는 아래 "소스에서 빌드")
2. 원하는 폴더에 압축 해제
3. 관리자 권한 PowerShell에서:
   ```powershell
   powershell -ExecutionPolicy Bypass -File install.ps1
   ```
   스크립트가 필요 시 자동 UAC 권한 상승. 파일을 `C:\Program Files\KorJpnIme\`
   에 복사하고 COM 서버 등록, TSF 프로파일 레지스트리 항목 임포트 수행.

4. **로그아웃 후 다시 로그인.** ctfmon이 IME 프로파일을 세션 단위로
   캐싱하므로 새 세션에서만 깨끗하게 인식됨.

5. 설정 → 시간 및 언어 → 언어 → 한국어 → 언어 옵션 → 키보드 추가 →
   **Korean-Japanese IME**.

6. 일본어 입력 시 `Win + Space`로 전환.

### 제거

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\KorJpnIme\uninstall.ps1"
# -RemoveFiles 플래그 추가 시 설치 디렉토리도 함께 삭제
```

## 설정

설정은 `%APPDATA%\KorJpnIme\settings.ini` 에 있습니다 (첫 실행 시 기본값으로
자동 생성). 편집 후 저장하면 다음 키스트로크에서 바로 반영됩니다.

```ini
[Hotkeys]
KatakanaToggle = RAlt+K          ; 수정자: Ctrl Shift Alt LAlt RAlt Win

[Behavior]
FullWidthAscii      = true       ; IME 켜진 상태에서 1 → １ 등
UserDictLearn       = true       ; 선택한 한자 기억
UserDictMaxEntries  = 5000       ; LFU 정리 상한; 0 = 무제한
```

## 소스에서 빌드

### 필요 항목

- Windows 10/11
- Visual Studio 2022 Build Tools (MSVC v143 + Windows 10 SDK)
- Python 3.10+ (데이터 빌드 전용, 런타임에는 불필요)

### 빌드 순서

```cmd
:: 1. Mozc OSS 데이터 소스 다운로드 (~95 MB TSV)
cd dict\mozc_src
fetch.sh           :: bash 또는 WSL

:: 2. 바이너리 엔진 입력 파일 생성 (~71 MB 출력)
cd ..
python build_viterbi_data.py

:: 3. DLL 빌드 + 배포 폴더 번들
cd ..
tools\make_release.bat
```

출력: `C:\Temp\KorJpnIme_release\` — DLL, 사전, .reg 파일, 설치/제거
스크립트, `LICENSES.txt` 포함. 그대로 zip으로 묶어서 배포 가능.

### 테스트

```cmd
tools\build_tests.bat
```

56개 단위 테스트 실행 (HangulComposer, BatchimLookup, Viterbi smoke).
exit code 0 이면 전체 통과.

## 아키텍처

```
mapping/syllables.yaml        한국어 음절 → 카나 매핑 테이블 (~150 엔트리)
   ↓ tsf/tools/gen_table.py
tsf/generated/
   ├── mapping_table.h        이진 검색용 정렬된 constexpr Entry[]
   └── batchim_rules.h        받침 → 카나 접미사 규칙 (소쿠온/하츠온)

dict/
   ├── jpn_dict.txt           레거시 텍스트 사전 (kana → kanji TSV, 18 MB)
   ├── kj_dict.bin            lid/rid/cost 포함 rich 사전 (바이너리, 57 MB)
   ├── kj_conn.bin            Mozc bigram 비용 행렬 (바이너리, 14 MB)
   ├── LICENSES.txt           NAIST IPAdic + Mozc + 오키나와 저작권 표기
   ├── build_viterbi_data.py  Mozc OSS 소스에서 kj_*.bin 빌드
   └── build_dict_mozc.py     동일 소스에서 jpn_dict.txt 빌드

tsf/src/
   ├── dllmain.cpp            DLL 엔트리, IClassFactory, 등록
   ├── KorJpnIme.cpp          ITfTextInputProcessor + ITfDisplayAttributeProvider
   ├── KeyHandler.cpp         ITfKeyEventSink + viterbi 기반 후보 빌드
   ├── HangulComposer.cpp     순수 2벌식 상태 머신 (독립 테스트 가능)
   ├── Composition.cpp        TSF edit session 통한 preedit + commit
   ├── DisplayAttributes.cpp  ITfDisplayAttributeInfo (점선 파란 밑줄)
   ├── BatchimLookup.h        받침 규칙 포함 단일 음절 한국어 → 카나
   ├── Dictionary.cpp         레거시 텍스트 사전 리더 (mmap jpn_dict.txt)
   ├── RichDictionary.cpp     바이너리 사전 리더 (mmap kj_dict.bin)
   ├── Connector.cpp          바이너리 연결 비용 행렬 (mmap kj_conn.bin)
   ├── Viterbi.cpp            RichDictionary + Connector 위 top-K viterbi
   ├── UserDict.cpp           사용자 학습 사전 + LFU 정리
   ├── Settings.cpp           %APPDATA% settings.ini + 핫리로드
   ├── CandidateWindow.cpp    Win32 팝업, 마우스 + 키보드 네비게이션
   └── KanaConv.h             히라가나 ↔ 카타카나 변환 헬퍼

tsf/tests/                    미니멀 헤더 온리 테스트 러너 (56 cases)
tools/                        install / uninstall / make_release / build_tests
```

## 제약 사항

- **재변환 (`ITfFnReconversion`) 미구현**. 이미 확정된 문자열을 선택해
  다른 변환을 요청하는 표준 플로우는 추후 작업. 우회: 삭제 후 재입력.
- **최초 설치 및 업데이트 시 로그아웃/로그인 사이클 필수**. ctfmon이
  로드된 TIP DLL을 세션 단위로 캐싱하기 때문 (TSF 설계상 정상 동작).
- **Windows 11에서 테스트.** Windows 10도 TSF 인터페이스가 같으므로
  동작 예상이지만 CI에서 실행하지 않음.
- **UserDict 학습은 첫 segment 랭킹에만 영향.** Viterbi path 자체의
  비용에 사용자 선택이 반영되지 않음. 추후 개선 예정.
- **Top-K viterbi K=5 로 고정.** 아직 settings 노출 안 됨.
- **트레이 아이콘 없음.** 모드 상태는 preedit / 후보창으로만 확인 가능.

## 라이선스

본 프로젝트는 **MIT** 라이선스입니다 (`LICENSE` 참조).

번들된 사전 데이터 (`jpn_dict.txt`, `kj_dict.bin`, `kj_conn.bin`) 는
Google Mozc OSS (BSD-3) 기반이며, 이는 NAIST IPAdic 및 오키나와 퍼블릭
도메인 사전에서 파생. 완전한 제3자 저작권 표기는 `dict/LICENSES.txt` 에
포함되어 있으며, 데이터와 함께 재배포 시 반드시 동봉해야 합니다.

## 기여

Issue 및 Pull Request 환영합니다. 큰 변경의 경우 이슈로 먼저 논의해주세요.

버그 수정 시 기존 테스트 스위트 (`tools\build_tests.bat`) 는 통과해야
하며, 순수 로직 차원에서 테스트 가능한 경우 회귀 케이스를 추가해 주시기 바랍니다.
