from .candidate import Candidate, ConversionResult
from .base import ConversionEngine
from .dict_client import DictConversionEngine
from .jisho_client import JishoConversionEngine
from .mozc_client import MozcConversionEngine


def get_best_engine() -> ConversionEngine:
    """Return the best available engine in priority order:
    1. Mozc  — fastest, offline, production-quality (needs mozc_server running)
    2. Jisho — online, PoC-quality (needs internet)
    3. Dict  — offline index from UniDic (fallback)
    """
    mozc = MozcConversionEngine()
    if mozc.is_available():
        # Verify server actually starts (not just binary exists)
        try:
            mozc._ensure_connection()
            if mozc._ready:
                return mozc
        except Exception:
            pass
        mozc.close()

    jisho = JishoConversionEngine()
    if jisho.is_available():
        return jisho

    return DictConversionEngine()
