# Dictionary

Builds the kana → kanji conversion dictionary used by the IME's candidate window.

## Source

`mecab-ipadic-2.7.0-20070801` — public domain Japanese morphological dictionary
(IPA / MeCab Project). Get it from SourceForge:

```bash
curl -L 'https://sourceforge.net/projects/mecab/files/mecab-ipadic/2.7.0-20070801/mecab-ipadic-2.7.0-20070801.tar.gz/download' \
     -o mecab-ipadic.tgz
tar xzf mecab-ipadic.tgz
```

## Build

```bash
python3 build_dict.py /path/to/mecab-ipadic-2.7.0-20070801 --out jpn_dict.txt
```

This processes ~26 EUC-JP-encoded CSV files (~390K lines), filters out pure-kana
surfaces and entries with no kanji, deduplicates by surface, and writes a
sorted UTF-8 text dictionary.

## Output format

```
# kor_based_jap dict v1<TAB>keys=N<TAB>entries=M
<kana><TAB><kanji_1><TAB><kanji_2>...
<kana><TAB><kanji_1><TAB><kanji_2>...
...
```

- One line per unique reading (hiragana).
- Kanji entries are sorted by ascending mecab cost (most frequent first).
- Sorted by kana for binary-search lookup at runtime.

## Stats (default settings)

| Metric | Value |
|--------|-------|
| Lines processed | ~392K |
| Entries kept | ~300K |
| Unique kana keys | ~180K |
| File size | ~6 MB |

## Tuning

`build_dict.py` flags:

- `--min-cost N` / `--max-cost N` — filter by mecab cost (lower = more common)
- `--top N` — cap candidates per kana key (default 30)
