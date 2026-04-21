# KorJpnIme v1.0.0

최초 공개 릴리스 — 한국어 2벌식 키보드 레이아웃으로 일본어를 입력할 수
있는 Windows TSF IME입니다. Mozc 수준의 한자 변환 품질을 제공합니다.

> 🌏 Languages: [English](https://github.com/ccy5123/kor_based_jap/blob/v1.0.0/docs/RELEASE_NOTES_v1.0.0.md) · **한국어** · [日本語](https://github.com/ccy5123/kor_based_jap/blob/v1.0.0/docs/RELEASE_NOTES_v1.0.0.ja.md)

## 설치 방법

1. `KorJpnIme-v1.0.0.zip`을 다운로드 (아래 첨부)
2. 원하는 폴더에 압축 해제
3. 관리자 권한 PowerShell에서:
   ```powershell
   powershell -ExecutionPolicy Bypass -File install.ps1
   ```
4. **로그아웃 후 다시 로그인** (ctfmon이 새 TIP를 깨끗하게 로드하려면 필수)
5. 설정 → 시간 및 언어 → 언어 → 한국어 → 언어 옵션 → 키보드 추가 →
   *Korean-Japanese IME* 선택
6. 일본어 입력 시 `Win+Space`로 전환

자세한 내용은 번들된 `README.md`와 `CHANGELOG.md` 참조.

## 주요 기능

- **한국어 2벌식 → 일본어 카나** — 복합 모음(ㅗ+ㅏ=ㅘ → わ), 복합 종성
  (ㄴ+ㅈ=ㄵ), 하츠온(ん) / 소쿠온(っ) 자동 인식
- **Mozc OSS 사전** — 약 75만 kana 키 / 129만 엔트리, 품사 ID와 비용값 포함
- **Top-K Viterbi 분절** — Mozc의 2672×2672 bigram 연결 비용 행렬 사용.
  「わたしの」같은 복합형이 `私 + の`로 자동 분절되어 **`私の`가 단일 후보로** 상단에 제공
- **동사 활용 지원** — Mozc suffix 테이블(る/た/ます/ない 등)을 통해
  たべます / よみました / きれいです 등이 정확히 분절됨
- **외래어 자동 카타카나 변환** — 한자 경로가 없는 경우 `한바아가아` →
  후보 리스트에 `ハンバーガー` 자동 제공
- **세 가지 특수 키 패턴** — 한국어에서 의미 없는 입력 시퀀스를 일본어
  격조사로 재활용:
  - `ㅇ-ㅗ-ㅗ` → を (목적격)
  - `ㅇ-ㅘ-ㅏ` → は (주제)
  - `ㅇ-ㅔ-ㅔ` → へ (방향)

  일본어 장음(おお, ええ 등)은 영향 없음 — 그쪽은 `ㅇ-ㅗ-ㅇ-ㅗ`처럼 명시적
  ㅇ가 사이에 필요하기 때문
- **사용자 학습 사전** — 선택한 한자가 다음번에 상위로 bubble-up.
  LFU 방식으로 오래된 항목 자동 정리 (기본 5000 엔트리 상한, `settings.ini`에서 조정)
- **설정 파일 핫리로드** — `%APPDATA%\KorJpnIme\settings.ini` 편집 시 다음
  키 입력 시점에 즉시 반영 (로그아웃 불필요)
- **카타카나 모드 토글** — 기본 `RAlt+K` (설정 파일에서 변경 가능)
- **MS-IME / Mozc 스타일 preedit** — 점선 파란 밑줄로 변환 중 텍스트 표시

## 알려진 제약

- **재변환(`ITfFnReconversion`) 미구현** — 이미 확정된 일본어를 선택해서
  다시 변환하는 기능. 우회: 삭제 후 재입력
- **최초 설치 / 업데이트 시 로그아웃 → 로그인 필요** — ctfmon이 TIP DLL을
  세션 단위로 캐싱하는 TSF 특성
- **사용자 학습은 첫 segment 랭킹에만 영향** — viterbi path cost 자체에
  학습 결과가 반영되지는 않음 (향후 개선 예정)
- **Top-K 값은 5로 하드코딩** — settings.ini 노출 예정
- **Windows 11에서 주로 검증** — Windows 10도 동작 예상되나 CI에서는 미실행

## 번들된 외부 데이터

`jpn_dict.txt` / `kj_dict.bin` / `kj_conn.bin`은 Google Mozc OSS(BSD-3)
기반이며, Mozc 데이터 자체도 NAIST IPAdic 및 오키나와 퍼블릭 도메인
사전에서 파생됐습니다. 완전한 저작권 표기는 번들된 `dict/LICENSES.txt`에
포함되어 있으며, 데이터 파일과 함께 재배포 시 반드시 동봉해야 합니다.

## 파일 해시

다운로드 후 위변조 검증:

```powershell
Get-FileHash KorJpnIme-v1.0.0.zip -Algorithm SHA256
```

- **SHA256**: `9CC16F0E2B784696F22689F1F0F4F0DA49B5423784E20D193C1E57F052767DE3`
- **MD5**: `D48BFEF81A602B585066F200934303E8`
