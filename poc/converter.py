"""Korean -> Japanese kana converter (PoC).

Reads mapping/syllables.yaml + mapping/batchim.yaml.
Decomposes Hangul syllables, applies mapping, handles batchim rules.

Usage:
    python converter.py "키미오"
    python converter.py < test_cases.txt
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.stderr.write("pyyaml required: pip install pyyaml\n")
    sys.exit(1)


CHO_LIST = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
JUNG_LIST = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
JONG_LIST = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

HANGUL_BASE = 0xAC00
HANGUL_END = 0xD7A3

# Vowel fallback: not-in-table 모음을 다른 모음 syllable로 lookup 후 suffix 부착
VOWEL_FALLBACK = {
    "ㅐ": ("ㅔ", "い"),  # 장음 시퀀스 (캐 -> けい)
    "ㅓ": ("ㅗ", ""),    # 일본어에 없는 모음 -> お 흡수
    "ㅕ": ("ㅛ", ""),    # ㅕ -> よ 흡수
    "ㅡ": ("ㅜ", ""),    # ㅡ -> う 흡수 (테이블 미존재 시)
}

# 초성 alias: ㅉ는 ㅊ로 alias (짱 -> ちゃん 같은 호칭 표기 위해)
CHO_ALIAS = {
    "ㅉ": "ㅊ",
}


def is_hangul_syllable(ch: str) -> bool:
    return len(ch) == 1 and HANGUL_BASE <= ord(ch) <= HANGUL_END


def decompose(syllable: str) -> tuple[str, str, str]:
    code = ord(syllable) - HANGUL_BASE
    cho_idx = code // (21 * 28)
    jung_idx = (code % (21 * 28)) // 28
    jong_idx = code % 28
    jong_ch = JONG_LIST[jong_idx]
    return CHO_LIST[cho_idx], JUNG_LIST[jung_idx], "" if jong_ch == " " else jong_ch


def compose(cho: str, jung: str, jong: str = "") -> str:
    cho_idx = CHO_LIST.index(cho)
    jung_idx = JUNG_LIST.index(jung)
    jong_idx = JONG_LIST.index(jong) if jong else 0
    code = (cho_idx * 21 + jung_idx) * 28 + jong_idx + HANGUL_BASE
    return chr(code)


def lookup_kana(cho: str, jung: str, syllables: dict, has_batchim: bool = False) -> str | None:
    bare = compose(cho, jung)
    if bare in syllables:
        return syllables[bare]
    if jung in VOWEL_FALLBACK:
        sub_jung, suffix = VOWEL_FALLBACK[jung]
        # ㅐ + 받침: い 생략 (외래어 표기 자연스럽게: 캠 -> けん, 책 -> ちぇく)
        if has_batchim and jung == "ㅐ":
            suffix = ""
        sub_bare = compose(cho, sub_jung)
        if sub_bare in syllables:
            return syllables[sub_bare] + suffix
    return None


def is_sokuon(jong: str, next_cho: str | None, rules: dict) -> bool:
    if next_cho is None or next_cho == "ㅇ":
        return False
    if jong in set(rules.get("sokuon_universal_markers", [])):
        return True
    strict = rules.get("sokuon_strict", {}) or {}
    return next_cho in strict.get(jong, [])


def convert(text: str, syllables: dict, batchim_rules: dict) -> str:
    out: list[str] = []
    chars = list(text)
    n = len(chars)
    hatsuon = set(batchim_rules.get("hatsuon", []))
    sokuon_always = set(batchim_rules.get("sokuon_always", []))
    sokuon_terminal = set(batchim_rules.get("sokuon_terminal", []))
    foreign_batchim = batchim_rules.get("foreign_batchim", {}) or {}

    i = 0
    while i < n:
        ch = chars[i]
        # Hyphen -> 장음 부호 ー
        if ch == "-":
            out.append("ー")
            i += 1
            continue
        if not is_hangul_syllable(ch):
            out.append(ch)
            i += 1
            continue

        cho, jung, jong = decompose(ch)
        cho = CHO_ALIAS.get(cho, cho)
        kana = lookup_kana(cho, jung, syllables, has_batchim=bool(jong))
        if kana is None:
            out.append(f"[?{ch}]")
            i += 1
            continue
        out.append(kana)

        if jong:
            next_cho = None
            if i + 1 < n and is_hangul_syllable(chars[i + 1]):
                next_raw, _, _ = decompose(chars[i + 1])
                next_cho = CHO_ALIAS.get(next_raw, next_raw)

            if jong in hatsuon:
                out.append("ん")
            elif jong in sokuon_always:
                out.append("っ")
            elif jong in sokuon_terminal and next_cho is None:
                out.append("っ")
            elif is_sokuon(jong, next_cho, batchim_rules):
                out.append("っ")
            elif jong in foreign_batchim:
                out.append(foreign_batchim[jong])
            else:
                out.append(f"[?받침{jong}]")
        i += 1

    return "".join(out)


def load_tables(mapping_dir: Path) -> tuple[dict, dict]:
    syllables = yaml.safe_load((mapping_dir / "syllables.yaml").read_text(encoding="utf-8"))
    batchim_rules = yaml.safe_load((mapping_dir / "batchim.yaml").read_text(encoding="utf-8"))
    return syllables, batchim_rules


def main() -> int:
    mapping_dir = Path(__file__).resolve().parent.parent / "mapping"
    syllables, batchim_rules = load_tables(mapping_dir)

    if len(sys.argv) > 1:
        for arg in sys.argv[1:]:
            print(convert(arg, syllables, batchim_rules))
        return 0

    for line in sys.stdin:
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        # tab-separated: input <TAB> expected (expected ignored, used by test runner)
        parts = line.split("\t")
        src = parts[0].strip()
        if not src:
            continue
        result = convert(src, syllables, batchim_rules)
        if len(parts) >= 2:
            expected = parts[1].strip()
            mark = "OK" if result == expected else "DIFF"
            print(f"[{mark}] {src} -> {result}  (expected: {expected})")
        else:
            print(f"{src} -> {result}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
