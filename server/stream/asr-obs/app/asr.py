from __future__ import annotations

from typing import Protocol
import logging
import tempfile

import numpy as np

from .events import Segment

log = logging.getLogger("asr")


class Asr(Protocol):
    def transcribe(self, segment: Segment) -> str: ...


class GigaAmAsr:
    def __init__(self, model_name: str, sample_rate: int, device: str):
        import gigaam

        self._sample_rate = sample_rate
        self._model = gigaam.load_model(model_name, device=device)

    def transcribe(self, segment: Segment) -> str:
        import soundfile as sf

        pcm = np.asarray(segment.pcm, dtype=np.float32)
        if pcm.size == 0:
            return ""
        with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
            sf.write(tmp.name, pcm, self._sample_rate)
            return self._model.transcribe(tmp.name).text


class AsrWithLogging:
    def __init__(self, delegate: Asr):
        self._delegate = delegate

    def transcribe(self, segment: Segment) -> str:
        text = self._delegate.transcribe(segment)
        log.info("[%.2f-%.2f] %s", segment.start, segment.end, text)
        return text


def asr(model_name: str, sample_rate: int, device: str) -> Asr:
    return AsrWithLogging(GigaAmAsr(model_name, sample_rate, device))
