from __future__ import annotations

from dataclasses import dataclass, asdict
import json

import numpy as np


@dataclass(frozen=True)
class Segment:
    start: float
    end: float
    pcm: np.ndarray
    is_final: bool = True


@dataclass(frozen=True)
class CaptionEvent:
    seq: int
    speaker: str
    text: str
    start: float
    end: float
    final: bool

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)
