from __future__ import annotations

from typing import Iterator, Protocol

import numpy as np

from .events import Segment


class Vad(Protocol):
    def segments(self, frames: Iterator[np.ndarray]) -> Iterator[Segment]: ...


class SileroVad:
    def __init__(self, sample_rate: int, max_segment_sec: float, partial_interval_sec: float):
        from silero_vad import load_silero_vad, VADIterator

        self._sample_rate = sample_rate
        self._max_samples = int(max_segment_sec * sample_rate)
        self._partial_samples = int(partial_interval_sec * sample_rate)
        self._model = load_silero_vad()
        self._iterator = VADIterator(self._model, sampling_rate=sample_rate)

    def segments(self, frames: Iterator[np.ndarray]) -> Iterator[Segment]:
        import torch

        buffer: list[np.ndarray] = []
        start_sample = 0
        cursor = 0
        emitted_len = 0
        collecting = False
        for frame in frames:
            cursor += len(frame)
            flags = self._iterator(torch.from_numpy(frame), return_seconds=False)
            if flags and "start" in flags:
                collecting = True
                start_sample = flags["start"]
                buffer = [frame]
                emitted_len = 0
                continue
            if not collecting:
                continue
            buffer.append(frame)
            total = sum(len(b) for b in buffer)
            if flags and "end" in flags:
                yield self._flush(buffer, start_sample, flags["end"], True)
                buffer, collecting, emitted_len = [], False, 0
            elif total >= self._max_samples:
                yield self._flush(buffer, start_sample, cursor, True)
                buffer, collecting, emitted_len = [], False, 0
            elif total - emitted_len >= self._partial_samples:
                emitted_len = total
                yield self._flush(buffer, start_sample, cursor, False)
        if collecting and buffer:
            yield self._flush(buffer, start_sample, cursor, True)

    def _flush(self, buffer: list[np.ndarray], start_sample: int, end_sample: int, is_final: bool) -> Segment:
        pcm = np.concatenate(buffer) if buffer else np.zeros(0, dtype=np.float32)
        return Segment(
            start=start_sample / self._sample_rate,
            end=end_sample / self._sample_rate,
            pcm=pcm,
            is_final=is_final,
        )


def vad(sample_rate: int, max_segment_sec: float, partial_interval_sec: float) -> Vad:
    return SileroVad(sample_rate, max_segment_sec, partial_interval_sec)
