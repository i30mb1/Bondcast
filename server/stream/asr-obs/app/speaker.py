from __future__ import annotations

import json
import logging
from pathlib import Path

import numpy as np

from .events import Segment

log = logging.getLogger("speaker")

GUEST_ID = "guest"
GUEST_NAME = "Кто-то"


def load_encoder():
    from speechbrain.inference.speaker import EncoderClassifier

    return EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        run_opts={"device": "cuda"},
    )


def embed(encoder, pcm: np.ndarray) -> np.ndarray:
    import torch

    signal = torch.from_numpy(np.asarray(pcm, dtype=np.float32)).unsqueeze(0)
    emb = encoder.encode_batch(signal).squeeze().detach().cpu().numpy()
    return emb / (np.linalg.norm(emb) + 1e-9)


class MultiSpeakerGate:
    """Классифицирует сегмент речи по списку именованных голосов.

    Список живёт на диске (voices.json + <id>.npy рядом) и монтируется панелью
    в контейнер как целая директория — перечитываем его по mtime на каждый
    classify(), поэтому добавление/переименование/удаление/смена порога голоса
    подхватываются без пересоздания контейнера и без новой ссылки в OBS
    (оверлей и так резолвит имя живьём через WS, не через query-параметры).
    """

    def __init__(self, voices_dir: str, sample_rate: int):
        self._voices_dir = Path(voices_dir)
        self._manifest_path = self._voices_dir / "voices.json"
        self._sample_rate = sample_rate
        self._encoder = load_encoder()
        self._mtime: float | None = None
        self._voices: list[dict] = []

    def _reload_if_changed(self) -> None:
        try:
            mtime = self._manifest_path.stat().st_mtime
        except OSError:
            if self._voices:
                log.info("манифест голосов пропал — держим последний известный список в памяти")
            self._voices = []
            self._mtime = None
            return
        if mtime == self._mtime:
            return
        try:
            entries = json.loads(self._manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            # Гонка с asr-enroll, дописывающим манифест в этот же момент — файл
            # может на мгновение оказаться недописанным. Не роняем пайплайн,
            # держим прошлый валидный список до следующего classify().
            log.warning("не удалось прочитать манифест голосов (%s) — использую прошлый список", e)
            return
        voices = []
        for entry in entries:
            npy_path = self._voices_dir / f"{entry['id']}.npy"
            try:
                if npy_path.stat().st_size == 0:
                    continue
                embedding = np.load(npy_path)
            except (OSError, ValueError) as e:
                log.warning("не удалось загрузить эталон голоса %s (%s) — пропускаю", entry.get("id"), e)
                continue
            voices.append({
                "id": entry["id"],
                "name": entry.get("name") or entry["id"],
                "threshold": float(entry.get("threshold", 0.25)),
                "embedding": embedding,
            })
        self._voices = voices
        self._mtime = mtime

    def default(self) -> tuple[str, str]:
        """Оптимистичный лейбл, пока сегмент ещё короче min_speaker_sec."""
        self._reload_if_changed()
        if self._voices:
            return self._voices[0]["id"], self._voices[0]["name"]
        return GUEST_ID, GUEST_NAME

    def classify(self, segment: Segment) -> tuple[str, str]:
        pcm = np.asarray(segment.pcm, dtype=np.float32)
        if pcm.size == 0:
            return GUEST_ID, GUEST_NAME
        self._reload_if_changed()
        if not self._voices:
            return GUEST_ID, GUEST_NAME
        emb = embed(self._encoder, pcm)
        best = max(self._voices, key=lambda v: float(np.dot(emb, v["embedding"])))
        similarity = float(np.dot(emb, best["embedding"]))
        if similarity >= best["threshold"]:
            log.info("[%.2f-%.2f] speaker=%s similarity=%.3f (порог %.3f)", segment.start, segment.end, best["name"], similarity, best["threshold"])
            return best["id"], best["name"]
        log.info("[%.2f-%.2f] speaker=%s similarity=%.3f (лучший порог %.3f не пройден)", segment.start, segment.end, GUEST_NAME, similarity, best["threshold"])
        return GUEST_ID, GUEST_NAME


def speaker_gate(voices_dir: str, sample_rate: int) -> MultiSpeakerGate:
    return MultiSpeakerGate(voices_dir, sample_rate)
