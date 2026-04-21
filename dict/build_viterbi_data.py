#!/usr/bin/env python3
"""Build viterbi-engine data files from Mozc OSS sources.

Outputs (little-endian, mmap-friendly binary):

    kj_dict.bin   -- rich kana dictionary with lid/rid/cost per surface
    kj_conn.bin   -- 2D connection cost matrix indexed by (lid, rid)

These are the runtime inputs for the future viterbi-based JapaneseConverter
(replaces the longest-prefix lookup over jpn_dict.txt).  jpn_dict.txt stays
shipped for backwards compatibility while the viterbi engine is opt-in.

------------------------------------------------------------------------
kj_dict.bin layout (all little-endian)
------------------------------------------------------------------------
    Header (16 bytes):
        char[4]  magic    = b"KJDV"
        uint32   version  = 1
        uint32   num_kana
        uint32   num_entries

    KanaEntry table  (num_kana * 16 bytes, sorted by kana UTF-8 bytes):
        uint32   kana_str_offset    -- bytes into String pool
        uint16   kana_str_len       -- bytes
        uint16   _pad               -- reserved (align)
        uint32   entries_start_idx  -- index into Entry table
        uint32   entries_count      -- # of entries belonging to this kana

    Entry table  (num_entries * 16 bytes):
        uint32   surface_str_offset
        uint16   surface_str_len
        int16    cost
        uint16   lid
        uint16   rid
        uint32   _pad

    String pool: contiguous UTF-8 bytes (kana strings then surface strings)

Lookup at runtime:
    binary search KanaEntry table by kana UTF-16 -> UTF-8 -> memcmp on
    the String pool slice.  Then iterate entries_start_idx .. +count to
    get all (surface, cost, lid, rid) candidates.

------------------------------------------------------------------------
kj_conn.bin layout
------------------------------------------------------------------------
    Header (16 bytes):
        char[4]  magic    = b"KJCN"
        uint32   version  = 1
        uint16   dim                -- = 2672 for current Mozc
        uint16   _pad
        uint32   _reserved

    Matrix (dim * dim * int16, row-major):
        cost[lid * dim + rid]   -- bigram cost for "left token has rid lid_left,
                                    right token has lid rid_right"

Total size:
    kj_dict.bin:  ~50 MB (1.29M entries, ~750K kana, full vocabulary)
    kj_conn.bin:  ~14 MB (2672 * 2672 * 2 bytes header overhead)
"""

from __future__ import annotations

import argparse
import glob
import os
import struct
import sys
from typing import Iterable


def read_dict_rows(src_dir: str) -> Iterable[tuple[str, int, int, int, str]]:
    paths = sorted(glob.glob(os.path.join(src_dir, "dictionary*.txt")))
    if not paths:
        raise SystemExit(
            f"No dictionary*.txt under {src_dir}.  "
            "Run dict/mozc_src/fetch.sh first."
        )
    for p in paths:
        with open(p, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 5:
                    continue
                reading, lid_s, rid_s, cost_s, surface = parts
                try:
                    yield reading, int(lid_s), int(rid_s), int(cost_s), surface
                except ValueError:
                    continue


def build_dict(src_dir: str, out_path: str) -> None:
    # Step 1: gather and group rows by kana.
    kana_to_entries: dict[str, list[tuple[str, int, int, int]]] = {}
    rows = 0
    for reading, lid, rid, cost, surface in read_dict_rows(src_dir):
        rows += 1
        if not reading or not surface:
            continue
        # Drop entries that won't fit in int16 cost (Mozc data fits today, but
        # be defensive against upstream changes).
        if not (-32768 <= cost <= 32767):
            continue
        if not (0 <= lid <= 0xFFFF) or not (0 <= rid <= 0xFFFF):
            continue
        kana_to_entries.setdefault(reading, []).append((surface, cost, lid, rid))

    # Step 2: sort kana keys (UTF-8 byte-wise so runtime memcmp matches).
    sorted_kana = sorted(kana_to_entries.keys(), key=lambda s: s.encode("utf-8"))

    # Step 3: layout the string pool.  De-duplicate identical strings (lots of
    # surfaces appear under multiple readings) so the pool stays compact.
    string_offsets: dict[str, int] = {}
    pool = bytearray()

    def intern(s: str) -> tuple[int, int]:
        if s in string_offsets:
            off = string_offsets[s]
        else:
            off = len(pool)
            pool.extend(s.encode("utf-8"))
            string_offsets[s] = off
        return off, len(s.encode("utf-8"))

    # Pre-intern all kana strings first so they cluster at the start of the pool
    # (better mmap locality for binary search).
    for k in sorted_kana:
        intern(k)
    for k in sorted_kana:
        for surface, _c, _l, _r in kana_to_entries[k]:
            intern(surface)

    # Step 4: build the entry table; each kana gets a contiguous slice.
    entries: list[tuple[int, int, int, int, int]] = []  # (off, len, cost, lid, rid)
    kana_records: list[tuple[int, int, int, int]] = []  # (off, len, start, count)

    for k in sorted_kana:
        kana_off, kana_len = string_offsets[k], len(k.encode("utf-8"))
        rows_for_k = kana_to_entries[k]
        # Sort by ascending cost so the runtime can stop early when scanning
        # for the cheapest candidate during lattice expansion.
        rows_for_k.sort(key=lambda r: (r[1], r[0]))
        start = len(entries)
        for surface, cost, lid, rid in rows_for_k:
            s_off, s_len = string_offsets[surface], len(surface.encode("utf-8"))
            entries.append((s_off, s_len, cost, lid, rid))
        kana_records.append((kana_off, kana_len, start, len(rows_for_k)))

    # Step 5: emit binary.
    with open(out_path, "wb") as f:
        f.write(b"KJDV")
        f.write(struct.pack("<III", 1, len(kana_records), len(entries)))
        for off, ln, start, count in kana_records:
            f.write(struct.pack("<IHHII", off, ln, 0, start, count))
        for s_off, s_len, cost, lid, rid in entries:
            f.write(struct.pack("<IHhHHI", s_off, s_len, cost, lid, rid, 0))
        f.write(pool)

    print(
        f"  rows scanned : {rows:,}\n"
        f"  unique kana  : {len(kana_records):,}\n"
        f"  total entries: {len(entries):,}\n"
        f"  string pool  : {len(pool):,} bytes "
        f"({len(string_offsets):,} unique strings)\n"
        f"  output       : {out_path} "
        f"({os.path.getsize(out_path):,} bytes)",
        file=sys.stderr,
    )


def build_connection(src_path: str, out_path: str) -> None:
    with open(src_path, encoding="utf-8") as f:
        dim = int(f.readline().strip())
        values = []
        for line in f:
            v = int(line.strip())
            if not (-32768 <= v <= 32767):
                raise SystemExit(f"connection cost {v} out of int16 range")
            values.append(v)

    expected = dim * dim
    if len(values) != expected:
        raise SystemExit(
            f"connection matrix size mismatch: got {len(values)}, "
            f"expected {expected}"
        )

    with open(out_path, "wb") as f:
        f.write(b"KJCN")
        f.write(struct.pack("<IHHI", 1, dim, 0, 0))
        # Matrix is already in row-major order (lid * dim + rid).
        f.write(struct.pack(f"<{expected}h", *values))

    print(
        f"  dim          : {dim} x {dim}\n"
        f"  values       : {len(values):,}\n"
        f"  output       : {out_path} "
        f"({os.path.getsize(out_path):,} bytes)",
        file=sys.stderr,
    )


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--src", default="mozc_src",
                   help="Mozc OSS source dir (with dictionary*.txt etc.)")
    p.add_argument("--dict-out", default="kj_dict.bin")
    p.add_argument("--conn-out", default="kj_conn.bin")
    args = p.parse_args(argv)

    print("=== building kj_dict.bin ===", file=sys.stderr)
    build_dict(args.src, args.dict_out)

    print("\n=== building kj_conn.bin ===", file=sys.stderr)
    conn_src = os.path.join(args.src, "connection_single_column.txt")
    build_connection(conn_src, args.conn_out)


if __name__ == "__main__":
    main()
