"""Abstract conversion engine interface.

All engines (Mozc, dict fallback, etc.) implement this.
"""

from __future__ import annotations
from abc import ABC, abstractmethod
from .candidate import ConversionResult


class ConversionEngine(ABC):
    @abstractmethod
    def convert(self, kana: str) -> ConversionResult:
        """Convert kana string to a ranked list of candidates."""

    @abstractmethod
    def is_available(self) -> bool:
        """Return True if this engine is ready to use."""

    def close(self) -> None:
        """Release resources. Override if needed."""
