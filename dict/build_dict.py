#!/usr/bin/env python3
"""
Build a kana→kanji dictionary from mecab-ipadic CSV files.

mecab-ipadic columns (EUC-JP encoded, CR-terminated lines):
    0  surface form    (e.g. 漢字)
    3  cost            (lower = more frequent)
    11 reading         (katakana, e.g. カンジ)

Output format (UTF-8, LF-terminated):
    kana<TAB>kanji1<TAB>kanji2<TAB>...
where kana is hiragana and kanji entries are sorted by ascending cost
(most frequent first), deduplicated within a kana group.

Usage:
    python3 build_dict.py /path/to/mecab-ipadic-2.7.0-20070801 \
                          --out jpn_dict.txt \
                          [--min-cost 0] [--max-cost 30000] [--top 50]
"""

import argparse
import csv
import io
import os
import sys
from collections import defaultdict


# Katakana → Hiragana: U+30A1..U+30F6 → U+3041..U+3096 (-0x60).
# ー (U+30FC) stays the same.
def katakana_to_hiragana(s: str) -> str:
    out = []
    for ch in s:
        c = ord(ch)
        if 0x30A1 <= c <= 0x30F6:
            out.append(chr(c - 0x60))
        else:
            out.append(ch)
    return "".join(out)


def is_pure_kana(s: str) -> bool:
    """True if s contains only katakana / hiragana / chouon — i.e. no kanji."""
    for ch in s:
        c = ord(ch)
        if not (
            0x3041 <= c <= 0x3096       # hiragana
            or 0x30A1 <= c <= 0x30FC    # katakana + chouon
        ):
            return False
    return True


def looks_like_kanji_word(s: str) -> bool:
    """True if s contains at least one CJK ideograph."""
    for ch in s:
        c = ord(ch)
        if 0x4E00 <= c <= 0x9FFF:
            return True
    return False


def process_csvs(ipa_dir: str, min_cost: int, max_cost: int, max_per_kana: int):
    # kana -> {surface: best_cost}
    table: dict[str, dict[str, int]] = defaultdict(dict)

    csv_files = [f for f in os.listdir(ipa_dir) if f.endswith(".csv")]
    csv_files.sort()
    print(f"Processing {len(csv_files)} CSVs from {ipa_dir}", file=sys.stderr)

    total_lines = 0
    total_kept = 0
    skipped_kana_only = 0
    skipped_no_kanji = 0
    skipped_cost = 0

    for fn in csv_files:
        path = os.path.join(ipa_dir, fn)
        with open(path, "rb") as fb:
            raw = fb.read()
        # Decode EUC-JP, normalise line endings (some files use CR only)
        text = raw.decode("euc-jp", errors="replace").replace("\r\n", "\n").replace("\r", "\n")
        for row in csv.reader(io.StringIO(text)):
            if len(row) < 12:
                continue
            total_lines += 1
            surface = row[0]
            try:
                cost = int(row[3])
            except ValueError:
                continue
            reading_kata = row[11]

            if not min_cost <= cost <= max_cost:
                skipped_cost += 1
                continue
            if not reading_kata:
                continue
            if is_pure_kana(surface):
                # 日本/にほん is what we want; カ/カ would be skipped.
                # Surface that's already pure kana adds no value (kana is shown verbatim
                # in the preedit anyway).
                skipped_kana_only += 1
                continue
            if not looks_like_kanji_word(surface):
                skipped_no_kanji += 1
                continue

            kana = katakana_to_hiragana(reading_kata)
            existing = table[kana].get(surface)
            if existing is None or cost < existing:
                table[kana][surface] = cost
            total_kept += 1

    print(
        f"Lines processed: {total_lines}, kept: {total_kept}, "
        f"skipped(pure-kana surface): {skipped_kana_only}, "
        f"skipped(no kanji): {skipped_no_kanji}, "
        f"skipped(cost out of range): {skipped_cost}",
        file=sys.stderr,
    )
    print(f"Unique kana keys: {len(table)}", file=sys.stderr)

    # Sort each kana's surfaces by ascending cost, cap to max_per_kana
    out_table: dict[str, list[str]] = {}
    for kana, surfaces in table.items():
        ordered = sorted(surfaces.items(), key=lambda kv: kv[1])
        out_table[kana] = [s for s, _ in ordered[:max_per_kana]]
    return out_table


def write_dict(table: dict[str, list[str]], path: str):
    keys = sorted(table.keys())
    total_entries = sum(len(v) for v in table.values())
    print(f"Writing {len(keys)} kana keys, {total_entries} total entries → {path}",
          file=sys.stderr)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        # Header line: version, key count, total entries
        f.write(f"# kor_based_jap dict v1\tkeys={len(keys)}\tentries={total_entries}\n")
        for kana in keys:
            f.write(kana)
            for kanji in table[kana]:
                f.write("\t")
                f.write(kanji)
            f.write("\n")


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("ipa_dir", help="Directory containing mecab-ipadic CSVs (EUC-JP)")
    p.add_argument("--out", default="jpn_dict.txt", help="Output text dictionary")
    p.add_argument("--min-cost", type=int, default=-2000)
    p.add_argument("--max-cost", type=int, default=30000)
    p.add_argument("--top", type=int, default=30,
                   help="Maximum candidates per kana key")
    args = p.parse_args(argv)

    table = process_csvs(args.ipa_dir, args.min_cost, args.max_cost, args.top)
    write_dict(table, args.out)


if __name__ == "__main__":
    main()
