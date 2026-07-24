from __future__ import annotations

import asyncio
import logging

from .asr import asr
from .audio_source import audio_source
from .config import Config
from .pipeline import pipeline
from .publisher import publisher
from .speaker import speaker_gate
from .vad import vad


def build_and_run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    cfg = Config.load()
    source = audio_source(cfg.source_url, cfg.sample_rate, cfg.reconnect_delay_sec)
    detector = vad(cfg.sample_rate, cfg.max_segment_sec, cfg.partial_interval_sec)
    recognizer = asr(cfg.asr_model, cfg.sample_rate, cfg.device)
    gate = speaker_gate(cfg.speaker_enabled, cfg.speaker_reference, cfg.sample_rate, cfg.speaker_threshold)
    pub = publisher(cfg.ws_host, cfg.ws_port)
    asyncio.run(pipeline(source, detector, recognizer, gate, pub, cfg.min_speaker_sec))


if __name__ == "__main__":
    build_and_run()
