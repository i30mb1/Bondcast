from __future__ import annotations

import logging
from pathlib import Path
from typing import Protocol

import numpy as np

from .events import Segment

log = logging.getLogger("speaker")

HOST = "host"
GUEST = "guest"


class SpeakerGate(Protocol):
    def classify(self, segment: Segment) -> str: ...


class AlwaysHostGate:
    def classify(self, segment: Segment) -> str:
        return HOST


class EcapaSpeakerGate:
    def __init__(self, reference_path: str, sample_rate: int, threshold: float):
        from speechbrain.inference.speaker import EncoderClassifier

        self._sample_rate = sample_rate
        self._threshold = threshold
        self._reference = np.load(reference_path)
        self._encoder = EncoderClassifier.from_hparams(
            source="speechbrain/spkrec-ecapa-voxceleb",
            run_opts={"device": "cuda"},
        )

    def embed(self, pcm: np.ndarray) -> np.ndarray:
        import torch

        signal = torch.from_numpy(np.asarray(pcm, dtype=np.float32)).unsqueeze(0)
        emb = self._encoder.encode_batch(signal).squeeze().detach().cpu().numpy()
        return emb / (np.linalg.norm(emb) + 1e-9)

    def classify(self, segment: Segment) -> str:
        pcm = np.asarray(segment.pcm, dtype=np.float32)
        if pcm.size == 0:
            return GUEST
        emb = self.embed(pcm)
        similarity = float(np.dot(emb, self._reference))
        speaker = HOST if similarity >= self._threshold else GUEST
        log.info("[%.2f-%.2f] speaker=%s similarity=%.3f (порог %.3f)", segment.start, segment.end, speaker, similarity, self._threshold)
        return speaker


def speaker_gate(enabled: bool, reference_path: str, sample_rate: int, threshold: float) -> SpeakerGate:
    ref = Path(reference_path)
    if not enabled or not ref.is_file() or ref.stat().st_size == 0:
        log.info("speaker gate: выключен (enabled=%s, эталон %s) — все реплики будут host", enabled, reference_path)
        return AlwaysHostGate()
    log.info("speaker gate: ECAPA включён, эталон %s, порог %.3f", reference_path, threshold)
    return EcapaSpeakerGate(reference_path, sample_rate, threshold)
