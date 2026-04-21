"""End-to-end demo: Korean input -> kana -> kanji candidates.

Usage:
    python engine/demo.py "키미오"
    python engine/demo.py "삿짱"
    python engine/demo.py               # interactive mode
"""

from __future__ import annotations
import sys
from pathlib import Path

# Resolve project root for imports
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from poc.converter import convert, load_tables
from engine import get_best_engine, MozcConversionEngine, DictConversionEngine
from engine.jisho_client import JishoConversionEngine


def pipeline(korean: str, syllables: dict, batchim_rules: dict, engine) -> None:
    kana = convert(korean, syllables, batchim_rules)
    result = engine.convert(kana)
    print(f"\n입력(한글): {korean}")
    print(f"가나 변환:  {kana}")
    print(result.display())


def main() -> None:
    mapping_dir = ROOT / "mapping"
    syllables, batchim_rules = load_tables(mapping_dir)

    engine = get_best_engine()
    engine_name = type(engine).__name__
    labels = {
        MozcConversionEngine: "Mozc (오프라인, 프로덕션)",
        JishoConversionEngine: "Jisho API (온라인 PoC)",
        DictConversionEngine:  "UniDic 사전 (로컬 폴백)",
    }
    print(f"[엔진: {labels.get(type(engine), engine_name)}]")

    test_words = ["키미오", "삿짱", "아리가토오", "신붕", "캐", "와타시"]

    if len(sys.argv) > 1:
        words = sys.argv[1:]
    else:
        words = test_words
        print(f"\n테스트 단어: {', '.join(words)}")
        print("─" * 50)

    for word in words:
        pipeline(word, syllables, batchim_rules, engine)

    if len(sys.argv) == 1:
        print("\n─" * 50)
        print("인터랙티브 모드 (Ctrl+C로 종료):")
        try:
            while True:
                word = input("한글 입력> ").strip()
                if word:
                    pipeline(word, syllables, batchim_rules, engine)
        except (KeyboardInterrupt, EOFError):
            print("\n종료")

    engine.close()


if __name__ == "__main__":
    main()
