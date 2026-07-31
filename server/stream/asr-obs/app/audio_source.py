from __future__ import annotations

from typing import Iterator, Protocol
import logging
import subprocess
import time

import numpy as np

log = logging.getLogger("audio")


class AudioSource(Protocol):
    def frames(self) -> Iterator[np.ndarray]: ...
    def close(self) -> None: ...


class FfmpegAudioSource:
    def __init__(self, url: str, sample_rate: int, reconnect_delay: float, frame_samples: int = 512):
        self._url = url
        self._sample_rate = sample_rate
        self._reconnect_delay = reconnect_delay
        self._frame_samples = frame_samples
        self._proc: subprocess.Popen | None = None
        self._running = False

    def _spawn(self) -> subprocess.Popen:
        log.info("подключаюсь к источнику: %s", self._url)
        return subprocess.Popen(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", self._url,
                "-vn", "-ac", "1", "-ar", str(self._sample_rate),
                "-f", "s16le", "-",
            ],
            stdout=subprocess.PIPE,
        )

    def frames(self) -> Iterator[np.ndarray]:
        self._running = True
        chunk_bytes = self._frame_samples * 2
        while self._running:
            self._proc = self._spawn()
            assert self._proc.stdout is not None
            while self._running:
                raw = self._proc.stdout.read(chunk_bytes)
                if not raw or len(raw) < chunk_bytes:
                    break
                pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
                yield pcm
            self._terminate()
            if not self._running:
                break
            log.warning("источник оборвался, реконнект через %.1fс", self._reconnect_delay)
            time.sleep(self._reconnect_delay)

    def _terminate(self) -> None:
        if self._proc is None:
            return
        proc, self._proc = self._proc, None
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()

    def close(self) -> None:
        self._running = False
        self._terminate()


def audio_source(url: str, sample_rate: int, reconnect_delay: float) -> AudioSource:
    return FfmpegAudioSource(url, sample_rate, reconnect_delay)
