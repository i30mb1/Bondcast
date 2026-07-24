from __future__ import annotations

import argparse
import glob

import numpy as np
import soundfile as sf

from .config import Config
from .speaker import EcapaSpeakerGate


def enroll(samples_glob: str, out_path: str, sample_rate: int, threshold: float) -> None:
    gate = EcapaSpeakerGate.__new__(EcapaSpeakerGate)
    from speechbrain.inference.speaker import EncoderClassifier

    gate._sample_rate = sample_rate
    gate._threshold = threshold
    gate._reference = np.zeros(0)
    gate._encoder = EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        run_opts={"device": "cuda"},
    )
    embeddings = []
    for path in sorted(glob.glob(samples_glob)):
        pcm, sr = sf.read(path, dtype="float32")
        if pcm.ndim > 1:
            pcm = pcm.mean(axis=1)
        embeddings.append(gate.embed(pcm))
    if not embeddings:
        raise SystemExit(f"нет сэмплов по маске {samples_glob}")
    reference = np.mean(embeddings, axis=0)
    reference = reference / (np.linalg.norm(reference) + 1e-9)
    np.save(out_path, reference)
    print(f"эталон сохранён: {out_path} ({len(embeddings)} сэмплов)")


def main() -> None:
    cfg = Config.load()
    parser = argparse.ArgumentParser()
    parser.add_argument("samples", help="glob wav-файлов ведущего, напр. host/*.wav")
    parser.add_argument("--out", default=cfg.speaker_reference)
    args = parser.parse_args()
    enroll(args.samples, args.out, cfg.sample_rate, cfg.speaker_threshold)


if __name__ == "__main__":
    main()
