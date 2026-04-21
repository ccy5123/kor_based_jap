#!/usr/bin/env python3
"""Build a kana->kanji dictionary from Mozc OSS dictionary TSVs.

Mozc OSS dictionary files (UTF-8, TAB-separated, LF-terminated):
    column 0: reading      (hiragana,  e.g. わたし)
    column 1: left_id      (POS context, integer)
    column 2: right_id     (POS context, integer)
    column 3: cost         (lower = more frequent; range observed 0..18318)
    column 4: surface      (e.g. 私  /  渡し  /  ワタシ)

Source files: dictionary00.txt..dictionary09.txt under
src/data/dictionary_oss/ in https://github.com/google/mozc.
License: BSD-3 (Mozc) plus NAIST IPAdic attribution + Okinawa Dictionary.
The README.txt MUST be redistributed alongside the data.

Output format (UTF-8, LF-terminated) -- identical to build_dict.py so the
runtime Dictionary loader needs no changes:

    # kor_based_jap dict v1<TAB>keys=N<TAB>entries=M
    kana<TAB>kanji1<TAB>kanji2<TAB>...
    ...

Per-kana surfaces are deduped (keep minimum cost across all POS contexts)
and sorted by ascending cost (most frequent first), capped to --top.

Usage:
    python3 build_dict_mozc.py mozc_src/ \\
        --out jpn_dict.txt --max-cost 12000 --top 30
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
from collections import defaultdict
from typing import Iterable


# Katakana -> Hiragana: U+30A1..U+30F6 -> U+3041..U+3096 (-0x60).
# Note: Mozc readings are already hiragana, so this is only used to normalize
# rare entries that slipped through with a katakana reading column.
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
    """True iff s contains only hiragana / katakana / chouon."""
    for ch in s:
        c = ord(ch)
        if not (
            0x3041 <= c <= 0x3096       # hiragana
            or 0x30A1 <= c <= 0x30FC    # katakana + chouon (ー)
        ):
            return False
    return True


def looks_like_kanji_word(s: str) -> bool:
    """True iff s contains at least one CJK ideograph (U+4E00..U+9FFF)."""
    for ch in s:
        if 0x4E00 <= ord(ch) <= 0x9FFF:
            return True
    return False


def is_valid_reading(s: str) -> bool:
    """Reading column should be hiragana (with chouon allowed for loanwords)."""
    if not s:
        return False
    for ch in s:
        c = ord(ch)
        if not (0x3041 <= c <= 0x3096 or c == 0x30FC):
            return False
    return True


def iter_entries(paths: Iterable[str]):
    """Yield (reading, cost, surface) for each well-formed Mozc TSV row."""
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 5:
                    continue
                reading, _lid, _rid, cost_s, surface = parts
                try:
                    cost = int(cost_s)
                except ValueError:
                    continue
                if not reading or not surface:
                    continue
                yield reading, cost, surface


def build_table(
    src_dir: str,
    min_cost: int,
    max_cost: int,
    max_per_kana: int,
) -> dict[str, list[str]]:
    paths = sorted(glob.glob(os.path.join(src_dir, "dictionary*.txt")))
    if not paths:
        raise SystemExit(
            f"no dictionary*.txt found in {src_dir}. "
            "Run dict/mozc_src/fetch.sh first."
        )

    # kana -> {surface: best_cost}
    table: dict[str, dict[str, int]] = defaultdict(dict)

    counters = {
        "rows": 0,
        "kept": 0,
        "skip_cost": 0,
        "skip_kana_surface": 0,
        "skip_no_kanji": 0,
        "skip_bad_reading": 0,
    }

    for reading, cost, surface in iter_entries(paths):
        counters["rows"] += 1

        if not min_cost <= cost <= max_cost:
            counters["skip_cost"] += 1
            continue

        # Normalize katakana readings (rare in Mozc, but defensive).
        kana = katakana_to_hiragana(reading)
        if not is_valid_reading(kana):
            counters["skip_bad_reading"] += 1
            continue

        # Skip surfaces that are pure kana -- the runtime preedit already
        # shows these verbatim, no candidate value.  Hiragana<->katakana
        # conversion is handled by the explicit katakana mode (RAlt+K).
        if is_pure_kana(surface):
            counters["skip_kana_surface"] += 1
            continue

        # Require at least one CJK ideograph -- avoids ASCII / symbol / Latin
        # entries (Mozc has a few, e.g. "w" for laughter).
        if not looks_like_kanji_word(surface):
            counters["skip_no_kanji"] += 1
            continue

        prev = table[kana].get(surface)
        if prev is None or cost < prev:
            table[kana][surface] = cost
        counters["kept"] += 1

    print(
        "rows={rows} kept={kept} "
        "skip[cost={skip_cost} kana_surface={skip_kana_surface} "
        "no_kanji={skip_no_kanji} bad_reading={skip_bad_reading}]".format(**counters),
        file=sys.stderr,
    )
    print(f"unique kana keys: {len(table)}", file=sys.stderr)

    out: dict[str, list[str]] = {}
    for kana, surfaces in table.items():
        ordered = sorted(surfaces.items(), key=lambda kv: kv[1])
        out[kana] = [s for s, _ in ordered[:max_per_kana]]
    return out


def write_dict(table: dict[str, list[str]], path: str) -> None:
    keys = sorted(table.keys())
    total_entries = sum(len(v) for v in table.values())
    print(
        f"writing {len(keys)} kana keys, {total_entries} total entries -> {path}",
        file=sys.stderr,
    )
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(
            f"# kor_based_jap dict v1\tkeys={len(keys)}\tentries={total_entries}\n"
        )
        for kana in keys:
            f.write(kana)
            for kanji in table[kana]:
                f.write("\t")
                f.write(kanji)
            f.write("\n")


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    p.add_argument(
        "src_dir",
        help="Directory containing Mozc dictionary00.txt..dictionary09.txt "
        "(see dict/mozc_src/fetch.sh)",
    )
    p.add_argument("--out", default="jpn_dict.txt")
    p.add_argument("--min-cost", type=int, default=0)
    p.add_argument(
        "--max-cost",
        type=int,
        default=12000,
        help="Drop entries with cost above this (default 12000 ~ p99).",
    )
    p.add_argument(
        "--top",
        type=int,
        default=30,
        help="Maximum candidates per kana key (default 30).",
    )
    args = p.parse_args(argv)

    table = build_table(args.src_dir, args.min_cost, args.max_cost, args.top)
    write_dict(table, args.out)


if __name__ == "__main__":
    main()
