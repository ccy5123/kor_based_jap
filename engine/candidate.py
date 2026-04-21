"""Common data structures for conversion candidates."""

from __future__ import annotations
from dataclasses import dataclass, field


@dataclass
class Candidate:
    surface: str        # 표시 텍스트 (e.g. "君を")
    reading: str        # 읽기 (e.g. "きみを")
    score: float = 0.0  # 랭킹 점수 (높을수록 상위)
    source: str = ""    # 출처 엔진 (e.g. "mozc", "dict")

    def __repr__(self) -> str:
        return f"Candidate({self.surface!r}, reading={self.reading!r}, score={self.score:.2f})"


@dataclass
class ConversionResult:
    input_kana: str
    candidates: list[Candidate] = field(default_factory=list)
    engine: str = ""

    @property
    def top(self) -> Candidate | None:
        return self.candidates[0] if self.candidates else None

    def display(self) -> str:
        if not self.candidates:
            return f"[{self.input_kana}] → (후보 없음)"
        lines = [f"[{self.input_kana}] → {len(self.candidates)}개 후보:"]
        for i, c in enumerate(self.candidates[:10], 1):
            mark = "▶" if i == 1 else " "
            lines.append(f"  {mark} {i}. {c.surface}  ({c.reading})")
        return "\n".join(lines)
