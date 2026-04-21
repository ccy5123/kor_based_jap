# KorJpnIme — 韓国語キーボードで日本語入力

[![Release](https://img.shields.io/github/v/release/ccy5123/kor_based_jap?display_name=tag&sort=semver)](https://github.com/ccy5123/kor_based_jap/releases/latest)
[![License](https://img.shields.io/github/license/ccy5123/kor_based_jap)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/ccy5123/kor_based_jap/tests.yml?branch=main&label=tests)](https://github.com/ccy5123/kor_based_jap/actions/workflows/tests.yml)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11-blue)](https://github.com/ccy5123/kor_based_jap/releases/latest)
[![Stars](https://img.shields.io/github/stars/ccy5123/kor_based_jap?style=social)](https://github.com/ccy5123/kor_based_jap/stargazers)

**韓国語 2-beolsik (2 벌식) キーボードレイアウト**で日本語を入力できる
Windows TSF (Text Services Framework) 入力メソッドです。韓国語の音節
(와타시노、갓코우、한바아가아…) を入力すると KorJpnIme が日本語かな
に変換し、Mozc レベルの viterbi ベース分節エンジンで漢字候補を提示
します。

> 🌏 Languages: [English](README.md) · [한국어](README.ko.md) · **日本語**

```
와타시   +  Space   →   私 / 渡し / 渡 / ワタシ / わたし
와타시노 +  Space   →   私の / 渡しの / わたしの / ワタシノ
갓코우   +  Space   →   学校 / 月光 / がっこう / ガッコウ
ㅇㅗㅗ   (Space なし) →   を       (目的格助詞)
ㅇㅘㅏ   (Space なし) →   は       (主題助詞)
ㅇㅔㅔ   (Space なし) →   へ       (方向助詞)
한바아가아 + Space  →   ハンバーガー  (外来語の自動カタカナ化)
```

モード切替なし、ローマ字経由なし、覚えるべき別ホットキーなし。
他の日本語 IME と同じようにカーソルの横に候補ウィンドウが表示されます。

---

## 主な機能

- **韓国語 2-beolsik → 日本語かな** — 複合母音 (ㅗ+ㅏ=ㅘ → わ)、複合
  パッチム (ㄴ+ㅈ=ㄵ → 適切なマイグレーション) に完全対応
- **撥音 ん / 促音 っ の自動認識** — ㄴㅁㅇ → ん、ㅎ → っ、ㅅㅆ は
  末尾または子音の直前で → っ 等
- **Mozc OSS 辞書** — 約 75 万のかなキー、約 129 万エントリ、Mozc
  オリジナルコスト値付き
- **Viterbi top-K 分節** — Mozc の bigram 接続コスト行列を使用。
  わたしの のような複合形は `私の`、`渡しの` 等の分節済み候補として
  リスト上位に表示
- **動詞活用サポート** — Mozc の suffix テーブル統合により、完全活用形が
  辞書に無くても `たべます`、`よみました`、`きれいです` 等が正しく分節
- **外来語の自動カタカナ化** — 漢字経路が無い場合でも `한바아가아` の
  ような入力に対して `ハンバーガー` 候補を提供
- **日本語助詞の 3 特殊キーパターン** — 韓国語で意味を持たないシーケンスを
  再利用:
  - `ㅇ-ㅗ-ㅗ` → を
  - `ㅇ-ㅘ-ㅏ` → は
  - `ㅇ-ㅔ-ㅔ` → へ

  実際の長母音 (おお、ええ) には影響なし — 母音間に明示的な ㅇ が必要
- **ユーザー学習辞書** — 選択した漢字が次回上位に浮上。デフォルト 5000
  エントリ上限 (LFU プルーニング)、`settings.ini` で調整可能
- **設定ファイルのホットリロード** — `%APPDATA%\KorJpnIme\settings.ini`
  を編集すると次のキーストローク時に即反映。ログアウト不要
- **カタカナモードトグル** (設定可能、デフォルト `RAlt+K`)
- **カスタム表示属性** — preedit テキストを MS-IME / Mozc スタイルの
  点線青下線で描画

## クイックスタート

### インストール

1. GitHub release ページから最新の `KorJpnIme-vX.Y.Z.zip` をダウンロード
   (または下記「ソースからビルド」)
2. 任意のフォルダに解凍
3. 管理者権限の PowerShell で:
   ```powershell
   powershell -ExecutionPolicy Bypass -File install.ps1
   ```
   スクリプトは必要に応じて UAC で自動昇格。`C:\Program Files\KorJpnIme\`
   にファイルをコピーし、COM サーバーを登録、TSF プロファイルの
   レジストリエントリをインポートします。

4. **ログアウトして再ログイン。** ctfmon が IME プロファイルをセッション
   単位でキャッシュするため、新しいセッションでのみ正しく認識されます。

5. 設定 → 時刻と言語 → 言語 → 韓国語 → 言語のオプション → キーボードを
   追加 → **Korean-Japanese IME**。

6. 日本語を入力する時は `Win + Space` で切り替え。

### アンインストール

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\KorJpnIme\uninstall.ps1"
# -RemoveFiles フラグでインストールディレクトリも削除
```

## 設定

設定は `%APPDATA%\KorJpnIme\settings.ini` にあります (初回起動時に
デフォルト値で自動生成)。編集して保存すると次のキーストローク時に
反映されます。

```ini
[Hotkeys]
KatakanaToggle = RAlt+K          ; 修飾子: Ctrl Shift Alt LAlt RAlt Win

[Behavior]
FullWidthAscii      = true       ; IME オン時に 1 → １ 等
UserDictLearn       = true       ; 選択した漢字を記憶
UserDictMaxEntries  = 5000       ; LFU プルーニング上限; 0 = 無制限
```

## ソースからビルド

### 前提条件

- Windows 10/11
- Visual Studio 2022 Build Tools (MSVC v143 + Windows 10 SDK)
- Python 3.10+ (データビルド専用、ランタイムには不要)

### 手順

```cmd
:: 1. Mozc OSS データソースを取得 (~95 MB の TSV)
cd dict\mozc_src
fetch.sh           :: bash または WSL

:: 2. バイナリエンジン入力ファイルをビルド (~71 MB 出力)
cd ..
python build_viterbi_data.py

:: 3. DLL ビルド + 配布フォルダの作成
cd ..
tools\make_release.bat
```

出力: `C:\Temp\KorJpnIme_release\` — DLL、辞書、.reg ファイル、
インストール / アンインストールスクリプト、`LICENSES.txt` を含む。
そのまま zip にしてリリース可能。

### テスト

```cmd
tools\build_tests.bat
```

56 ケースのユニットテストスイートを実行 (HangulComposer、BatchimLookup、
Viterbi の smoke)。exit code 0 ならば全テスト通過。

## アーキテクチャ

```
mapping/syllables.yaml        韓国語音節 → かな対応テーブル (~150 エントリ)
   ↓ tsf/tools/gen_table.py
tsf/generated/
   ├── mapping_table.h        バイナリ検索用ソート済み constexpr Entry[]
   └── batchim_rules.h        パッチム → かな接尾辞ルール (促音 / 撥音)

dict/
   ├── jpn_dict.txt           レガシーテキスト辞書 (kana → kanji TSV, 18 MB)
   ├── kj_dict.bin            lid/rid/cost 付きリッチ辞書 (バイナリ, 57 MB)
   ├── kj_conn.bin            Mozc bigram コスト行列 (バイナリ, 14 MB)
   ├── LICENSES.txt           NAIST IPAdic + Mozc + 沖縄辞書の帰属表示
   ├── build_viterbi_data.py  Mozc OSS ソースから kj_*.bin をビルド
   └── build_dict_mozc.py     同ソースから jpn_dict.txt をビルド

tsf/src/
   ├── dllmain.cpp            DLL エントリ、IClassFactory、登録
   ├── KorJpnIme.cpp          ITfTextInputProcessor + ITfDisplayAttributeProvider
   ├── KeyHandler.cpp         ITfKeyEventSink + viterbi 駆動の候補生成
   ├── HangulComposer.cpp     純粋な 2-beolsik 状態マシン (独立テスト可)
   ├── Composition.cpp        TSF edit session による preedit + commit
   ├── DisplayAttributes.cpp  ITfDisplayAttributeInfo (点線青下線)
   ├── BatchimLookup.h        パッチムルールを含む単一音節 韓国語 → かな
   ├── Dictionary.cpp         レガシーテキスト辞書リーダー (mmap jpn_dict.txt)
   ├── RichDictionary.cpp     バイナリ辞書リーダー (mmap kj_dict.bin)
   ├── Connector.cpp          バイナリ接続コスト行列 (mmap kj_conn.bin)
   ├── Viterbi.cpp            RichDictionary + Connector 上の top-K viterbi
   ├── UserDict.cpp           ユーザー学習辞書 + LFU プルーニング
   ├── Settings.cpp           %APPDATA% settings.ini + ホットリロード
   ├── CandidateWindow.cpp    Win32 ポップアップ、マウス + キーボード操作
   └── KanaConv.h             ひらがな ↔ カタカナ変換ヘルパー

tsf/tests/                    ミニマルなヘッダオンリーテストランナー (56 cases)
tools/                        install / uninstall / make_release / build_tests
```

## 既知の制限

- **再変換 (`ITfFnReconversion`) 未実装。** 既に確定された文字列を
  選択して別の変換を要求する標準フローは今後の作業。回避策: 削除して
  再入力。
- **初回インストールと更新時にログアウト / ログインサイクルが必須。**
  ctfmon はロード済み TIP DLL をセッション単位でキャッシュするため
  (TSF の設計上の正常動作)。
- **Windows 11 で検証済み。** Windows 10 でも TSF インターフェースが
  同じなので動作すると予想されますが、CI では実行していません。
- **UserDict 学習は最初の segment の順位付けのみに影響。** Viterbi
  path 自体のコストにはユーザー選択が反映されません。将来改善予定。
- **Top-K viterbi は K=5 でハードコード。** まだ設定に公開されていません。
- **トレイアイコンなし。** モード状態は preedit / 候補ウィンドウでのみ
  確認可能。

## ライセンス

本プロジェクトは **MIT** ライセンスです (`LICENSE` 参照)。

同梱の辞書データ (`jpn_dict.txt`、`kj_dict.bin`、`kj_conn.bin`) は
Google Mozc OSS (BSD-3) 由来で、Mozc 自体も NAIST IPAdic および沖縄
パブリックドメイン辞書から派生しています。完全なサードパーティ帰属
表示は `dict/LICENSES.txt` に含まれており、データと一緒に再配布する
際は必ず同梱してください。

## 貢献

Issue および Pull Request を歓迎します。大きな変更の場合はまず issue
で議論してください。

バグ修正時は既存のテストスイート (`tools\build_tests.bat`) が通過する
必要があります。純粋なロジック層でテスト可能な場合は回帰ケースを
追加してください。
