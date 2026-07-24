from __future__ import annotations

from pathlib import Path
from typing import Protocol

import numpy as np

from .events import Segment

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
        return HOST if similarity >= self._threshold else GUEST


def speaker_gate(enabled: bool, reference_path: str, sample_rate: int, threshold: float) -> SpeakerGate:
    if not enabled or not Path(reference_path).is_file():
        return AlwaysHostGate()
    return EcapaSpeakerGate(reference_path, sample_rate, threshold)
