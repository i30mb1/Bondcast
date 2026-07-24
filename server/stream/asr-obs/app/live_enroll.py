from __future__ import annotations

import argparse
import time

import numpy as np

from .audio_source import audio_source
from .config import Config
from .events import Segment
from .speaker import EcapaSpeakerGate
from .vad import vad

_FRAME_SAMPLES = 512  # тот же размер, что и в живом пайплайне (audio_source.py) — VADIterator требует его строго


def _chunks(pcm: np.ndarray, frame_samples: int = _FRAME_SAMPLES):
    for i in range(0, len(pcm) - frame_samples + 1, frame_samples):
        yield pcm[i : i + frame_samples]


def enroll_live(source_url: str, sample_rate: int, duration_sec: float, out_path: str, threshold: float) -> None:
    from speechbrain.inference.speaker import EncoderClassifier

    gate = EcapaSpeakerGate.__new__(EcapaSpeakerGate)
    gate._sample_rate = sample_rate
    gate._threshold = threshold
    gate._reference = np.zeros(0)
    gate._encoder = EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        run_opts={"device": "cuda"},
    )

    print(f"слушаю {duration_sec:.0f}с — говорить должен только ведущий, без пауз", flush=True)
    src = audio_source(source_url, sample_rate, reconnect_delay=1.0)
    chunks: list[np.ndarray] = []
    start = time.time()
    try:
        for pcm in src.frames():
            chunks.append(pcm)
            if time.time() - start >= duration_sec:
                break
    finally:
        src.close()

    if not chunks:
        print("не удалось получить аудио с потока — эталон не сохранён", flush=True)
        raise SystemExit(1)

    full = np.concatenate(chunks)
    print(f"получено {len(full) / sample_rate:.1f}с аудио, ищу речь...", flush=True)

    # VAD прогоняем оффлайн по уже записанному буферу — теми же кусками по 512
    # сэмплов, что и в живом пайплайне, чтобы поведение совпадало 1:1.
    detector = vad(sample_rate, max_segment_sec=duration_sec, partial_interval_sec=duration_sec)
    embeddings = []
    segment: Segment
    for segment in detector.segments(_chunks(full)):
        if not segment.is_final or segment.pcm.size == 0:
            continue
        embeddings.append(gate.embed(segment.pcm))
        print(f"сегмент речи {segment.start:.1f}-{segment.end:.1f}с", flush=True)

    if not embeddings:
        print("речь не найдена в записи — эталон не сохранён, попробуй ещё раз и говори громче/дольше", flush=True)
        raise SystemExit(1)

    reference = np.mean(embeddings, axis=0)
    reference = reference / (np.linalg.norm(reference) + 1e-9)
    np.save(out_path, reference)
    print(f"эталон сохранён: {out_path} ({len(embeddings)} сегментов речи)", flush=True)


def main() -> None:
    cfg = Config.load()
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--duration", type=float, default=15.0)
    parser.add_argument("--out", default=cfg.speaker_reference)
    args = parser.parse_args()
    enroll_live(args.source_url, cfg.sample_rate, args.duration, args.out, cfg.speaker_threshold)


if __name__ == "__main__":
    main()
