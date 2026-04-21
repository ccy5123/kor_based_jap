"""Fallback conversion engine using fugashi + unidic-lite.

Builds a reverse index (reading -> surface forms) from the UniDic
morpheme dictionary, then scores candidates by corpus frequency.

Install: pip install fugashi unidic-lite
"""

from __future__ import annotations
import re
from collections import defaultdict
from functools import lru_cache

from .base import ConversionEngine
from .candidate import Candidate, ConversionResult

# 가나를 정규화: カタカナ -> ひらがな (UniDic readings are katakana)
_KA_BASE = ord("ァ")
_HI_BASE = ord("ぁ")
_KA_END  = ord("ン")


def kata_to_hira(text: str) -> str:
    result = []
    for ch in text:
        code = ord(ch)
        if _KA_BASE <= code <= _KA_END:
            result.append(chr(code - _KA_BASE + _HI_BASE))
        else:
            result.append(ch)
    return "".join(result)


def hira_to_kata(text: str) -> str:
    result = []
    for ch in text:
        code = ord(ch)
        if _HI_BASE <= code <= ord("ん"):
            result.append(chr(code - _HI_BASE + _KA_BASE))
        else:
            result.append(ch)
    return "".join(result)


class DictConversionEngine(ConversionEngine):
    """Reverse-index based engine built from UniDic via fugashi.

    First call to convert() triggers lazy index build (~2-3 seconds).
    Subsequent calls are fast (in-memory lookup).
    """

    def __init__(self) -> None:
        self._tagger = None
        self._index: dict[str, list[tuple[str, float]]] | None = None

    def is_available(self) -> bool:
        try:
            import fugashi  # noqa: F401
            return True
        except ImportError:
            return False

    def _ensure_index(self) -> None:
        if self._index is not None:
            return
        import fugashi
        self._tagger = fugashi.Tagger()
        self._index = self._build_index()

    def _build_index(self) -> dict[str, list[tuple[str, float]]]:
        """Build reading -> [(surface, freq)] reverse index from UniDic."""
        import fugashi
        import unidic_lite

        dic_dir = unidic_lite.DICDIR
        # Parse the lex.csv from unidic-lite for all entries
        lex_path = f"{dic_dir}/lex.csv"
        index: dict[str, list[tuple[str, float]]] = defaultdict(list)

        try:
            with open(lex_path, encoding="utf-8") as f:
                for line in f:
                    parts = line.rstrip("\n").split(",")
                    if len(parts) < 10:
                        continue
                    surface = parts[0]
                    # UniDic lex: col 0=surface, col 11=kana_reading (varies)
                    # cols: surface, left_id, right_id, cost, pos1..pos6, conj_type, conj_form, orth_base, pron, ...
                    # reading is typically parts[11] (pronunciation) or parts[9] (base form reading)
                    if len(parts) > 11:
                        reading_kata = parts[11]
                    elif len(parts) > 9:
                        reading_kata = parts[9]
                    else:
                        continue

                    if not reading_kata or not re.match(r"^[ァ-ンー]+$", reading_kata):
                        continue

                    reading_hira = kata_to_hira(reading_kata)
                    cost = int(parts[3]) if parts[3].lstrip("-").isdigit() else 10000
                    # Lower cost = more common. Convert to score (higher = better).
                    score = max(0.0, 1.0 - cost / 15000.0)

                    # Skip pure kana entries as redundant (keep kanji/mixed)
                    has_kanji = any("\u4e00" <= ch <= "\u9fff" for ch in surface)
                    is_symbol = bool(re.match(r"^[ぁ-ん]+$", surface))
                    if surface and (has_kanji or not is_symbol):
                        index[reading_hira].append((surface, score))
        except FileNotFoundError:
            pass

        # Sort each reading's list by score desc, deduplicate
        result: dict[str, list[tuple[str, float]]] = {}
        for reading, entries in index.items():
            seen: set[str] = set()
            deduped = []
            for surface, score in sorted(entries, key=lambda x: -x[1]):
                if surface not in seen:
                    seen.add(surface)
                    deduped.append((surface, score))
            result[reading] = deduped[:20]  # top-20 per reading
        return result

    def convert(self, kana: str) -> ConversionResult:
        self._ensure_index()
        assert self._index is not None

        candidates: list[Candidate] = []
        entries = self._index.get(kana, [])
        for surface, score in entries:
            candidates.append(Candidate(
                surface=surface,
                reading=kana,
                score=score,
                source="dict",
            ))

        # 가나 원문도 후보에 추가 (항상 선택 가능)
        if not any(c.surface == kana for c in candidates):
            candidates.append(Candidate(surface=kana, reading=kana, score=0.0, source="dict"))

        return ConversionResult(
            input_kana=kana,
            candidates=candidates,
            engine="dict",
        )
