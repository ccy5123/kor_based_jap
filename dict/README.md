# Dictionary

Builds the kana -> kanji conversion dictionary used by the IME's candidate window.

## Source: Google Mozc OSS dictionary (current default)

The shipped `jpn_dict.txt` is built from
[Mozc's OSS dictionary data](https://github.com/google/mozc/tree/master/src/data/dictionary_oss).
This is a curated superset of `mecab-ipadic-2.7.0`, with additional entries
collected from web corpora and manually-added compounds.  Cost values are
tuned by Google for their Japanese IME, so candidate ranking is generally
better than raw IPAdic.

Compared with the previous IPAdic build, Mozc gives roughly:

| Metric | IPAdic | Mozc |
|--------|-------:|-----:|
| Unique kana keys | 179 K | 506 K |
| Total entries    | 265 K | 736 K |
| File size        | 5.8 MB | 18 MB |

### Rebuild from upstream

```bash
cd dict/mozc_src
./fetch.sh                         # downloads ~58 MB of TSV from github.com/google/mozc
cd ..
python3 build_dict_mozc.py mozc_src/ --out jpn_dict.txt
```

### Tuning flags

`build_dict_mozc.py` takes:

- `--max-cost N` — drop entries with Mozc cost above N (default 12000 ~ p99)
- `--top N`      — cap candidates per kana key (default 30)
- `--min-cost N` — usually 0; raise to drop overly-common particle entries

## License

The Mozc dictionary data and the IPAdic / Okinawa dictionaries it derives
from are all redistributable under permissive terms.  Full attribution
text is in [`LICENSES.txt`](LICENSES.txt) — that file MUST be shipped
alongside `jpn_dict.txt` per the IPAdic terms.

## Output format

```
# kor_based_jap dict v1<TAB>keys=N<TAB>entries=M
<kana><TAB><kanji_1><TAB><kanji_2>...
<kana><TAB><kanji_1><TAB><kanji_2>...
```

- One line per unique reading (hiragana).
- Kanji entries sorted by ascending Mozc cost (most frequent first).
- Lines sorted by kana for binary-search lookup at runtime.
- UTF-8, LF-terminated.

## Legacy: build from mecab-ipadic CSVs

The original IPAdic-based builder is kept as `build_dict_ipadic.py` for
reference / fallback.  It expects the EUC-JP CSVs from
`mecab-ipadic-2.7.0-20070801`:

```bash
curl -L 'https://sourceforge.net/projects/mecab/files/mecab-ipadic/2.7.0-20070801/mecab-ipadic-2.7.0-20070801.tar.gz/download' \
     -o mecab-ipadic.tgz
tar xzf mecab-ipadic.tgz
python3 build_dict_ipadic.py mecab-ipadic-2.7.0-20070801/ --out jpn_dict.txt
```

Use this if you specifically want the smaller IPAdic-only vocabulary.
