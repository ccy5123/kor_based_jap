"""Jisho API-based conversion engine (online fallback).

Uses jisho.org dictionary API to look up kanji candidates by kana reading.
Handles multi-segment kana via greedy longest-match segmentation.

Install: pip install jisho-api
Note: Requires internet. Rate-limited (~1 req/sec). Replace with Mozc for production.
"""

from __future__ import annotations
import time
from functools import lru_cache

from .base import ConversionEngine
from .candidate import Candidate, ConversionResult

_RATE_LIMIT_SEC = 0.5   # minimum seconds between requests


@lru_cache(maxsize=512)
def _lookup(kana: str) -> list[tuple[str, str]]:
    """Return [(surface, reading)] for a kana reading. Cached."""
    try:
        from jisho_api.word import Word
        time.sleep(_RATE_LIMIT_SEC)
        result = Word.request(kana)
        if not result or not result.data:
            return []
        pairs = []
        for entry in result.data[:8]:
            slug = entry.slug
            if not entry.japanese:
                continue
            for jp in entry.japanese[:2]:
                reading = jp.reading or ""
                word = jp.word or slug
                # Only include entries whose reading starts with our query
                if reading.startswith(kana):
                    pairs.append((word, reading))
        return pairs
    except Exception:
        return []


def _segment(kana: str, max_len: int = 8) -> list[str]:
    """Greedily segment kana string into dictionary words (longest-match)."""
    segments: list[str] = []
    i = 0
    n = len(kana)
    while i < n:
        best_len = 1
        for length in range(min(max_len, n - i), 1, -1):
            chunk = kana[i:i + length]
            if _lookup(chunk):
                best_len = length
                break
        segments.append(kana[i:i + best_len])
        i += best_len
    return segments


class JishoConversionEngine(ConversionEngine):
    """Online Jisho-based engine. PoC quality — replace with Mozc for production."""

    def is_available(self) -> bool:
        try:
            from jisho_api.word import Word  # noqa: F401
            return True
        except ImportError:
            return False

    def convert(self, kana: str) -> ConversionResult:
        # Try whole string first (handles single compounds like しんぶん)
        pairs = _lookup(kana)
        if pairs:
            candidates = [
                Candidate(surface=s, reading=r, score=1.0 - i * 0.1, source="jisho")
                for i, (s, r) in enumerate(pairs)
            ]
            candidates.append(Candidate(surface=kana, reading=kana, score=0.0, source="jisho"))
            return ConversionResult(input_kana=kana, candidates=candidates, engine="jisho")

        # Segment and combine candidates for each segment
        segments = _segment(kana)
        if len(segments) == 1:
            # Single segment, no match → return kana as-is
            return ConversionResult(
                input_kana=kana,
                candidates=[Candidate(surface=kana, reading=kana, score=0.0, source="jisho")],
                engine="jisho",
            )

        # Build top-1 combination + fallback per segment
        top_surfaces: list[str] = []
        all_per_segment: list[list[tuple[str, str]]] = []
        for seg in segments:
            seg_pairs = _lookup(seg)
            all_per_segment.append(seg_pairs)
            top_surfaces.append(seg_pairs[0][0] if seg_pairs else seg)

        combined = "".join(top_surfaces)
        candidates: list[Candidate] = [
            Candidate(surface=combined, reading=kana, score=1.0, source="jisho"),
        ]
        # Add segment-level alternatives
        for seg, seg_pairs in zip(segments, all_per_segment):
            for s, r in seg_pairs[:3]:
                if s != seg:
                    cand = Candidate(surface=f"[{s}]", reading=r, score=0.5, source="jisho")
                    candidates.append(cand)
        candidates.append(Candidate(surface=kana, reading=kana, score=0.0, source="jisho"))

        return ConversionResult(input_kana=kana, candidates=candidates, engine="jisho")
