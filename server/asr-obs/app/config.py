from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os

import yaml


@dataclass(frozen=True)
class Config:
    source_url: str
    sample_rate: int
    asr_model: str
    device: str
    speaker_enabled: bool
    speaker_reference: str
    speaker_threshold: float
    ws_host: str
    ws_port: int
    max_segment_sec: float
    partial_interval_sec: float
    min_speaker_sec: float
    reconnect_delay_sec: float

    @staticmethod
    def load(path: str | None = None) -> "Config":
        data: dict = {}
        resolved = path or os.environ.get("ASR_OBS_CONFIG", "config.yaml")
        p = Path(resolved)
        if p.exists():
            data = yaml.safe_load(p.read_text()) or {}
        return Config(
            source_url=data.get("source_url", "srt://127.0.0.1:10080?streamid=live/stream"),
            sample_rate=int(data.get("sample_rate", 16000)),
            asr_model=data.get("asr_model", "v3_e2e_rnnt"),
            device=data.get("device", "cuda"),
            speaker_enabled=bool(data.get("speaker_enabled", True)),
            speaker_reference=data.get("speaker_reference", "reference.npy"),
            speaker_threshold=float(data.get("speaker_threshold", 0.25)),
            ws_host=data.get("ws_host", "0.0.0.0"),
            ws_port=int(data.get("ws_port", 8765)),
            max_segment_sec=float(data.get("max_segment_sec", 20.0)),
            partial_interval_sec=float(data.get("partial_interval_sec", 0.7)),
            min_speaker_sec=float(data.get("min_speaker_sec", 1.0)),
            reconnect_delay_sec=float(data.get("reconnect_delay_sec", 2.0)),
        )
