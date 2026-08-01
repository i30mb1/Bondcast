from __future__ import annotations

import argparse
import glob
import os

import numpy as np
import soundfile as sf

from .speaker import embed, load_encoder


def enroll(samples_glob: str, out_path: str) -> None:
    encoder = load_encoder()
    embeddings = []
    for path in sorted(glob.glob(samples_glob)):
        pcm, sr = sf.read(path, dtype="float32")
        if pcm.ndim > 1:
            pcm = pcm.mean(axis=1)
        embeddings.append(embed(encoder, pcm))
    if not embeddings:
        raise SystemExit(f"нет сэмплов по маске {samples_glob}")
    reference = np.mean(embeddings, axis=0)
    reference = reference / (np.linalg.norm(reference) + 1e-9)
    tmp_path = f"{out_path}.tmp.npy"
    np.save(tmp_path, reference)
    os.replace(tmp_path, out_path)
    print(f"эталон сохранён: {out_path} ({len(embeddings)} сэмплов)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("samples", help="glob wav-файлов голоса, напр. host/*.wav")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    enroll(args.samples, args.out)


if __name__ == "__main__":
    main()
