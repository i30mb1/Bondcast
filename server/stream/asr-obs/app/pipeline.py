from __future__ import annotations

import asyncio
import logging

from .asr import Asr
from .audio_source import AudioSource
from .events import CaptionEvent
from .publisher import Publisher
from .speaker import MultiSpeakerGate
from .vad import Vad

log = logging.getLogger("pipeline")


def _produce(source, vad, asr, gate, min_speaker_sec, loop, queue) -> None:
    seq = 0
    open_utt = False
    utt_speaker: tuple[str, str] | None = None
    last_text = ""
    try:
        for segment in vad.segments(source.frames()):
            if not open_utt:
                seq += 1
                open_utt = True
                utt_speaker = None
                last_text = ""

            text = asr.transcribe(segment).strip()
            if text:
                last_text = text

            duration = segment.end - segment.start
            if utt_speaker is None and (segment.is_final or duration >= min_speaker_sec):
                utt_speaker = gate.classify(segment)
            # Пока сегмент короче min_speaker_sec — оптимистичный дефолт гейта
            # (первый известный голос, а не жёстко "host" — с несколькими
            # голосами нет привилегированного), чтобы подпись не мигала.
            speaker_id, speaker_name = utt_speaker or gate.default()

            if segment.is_final:
                open_utt = False
                text = text or last_text
                if not text:
                    continue
            elif not text:
                continue

            event = CaptionEvent(
                seq=seq,
                speaker_id=speaker_id,
                speaker_name=speaker_name,
                text=text,
                start=segment.start,
                end=segment.end,
                final=segment.is_final,
            )
            asyncio.run_coroutine_threadsafe(queue.put(event), loop)
    finally:
        source.close()
        asyncio.run_coroutine_threadsafe(queue.put(None), loop)


async def pipeline(source: AudioSource, vad: Vad, asr: Asr, gate: MultiSpeakerGate, pub: Publisher, min_speaker_sec: float) -> None:
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue = asyncio.Queue()
    server_task = asyncio.create_task(pub.serve())
    producer = loop.run_in_executor(None, _produce, source, vad, asr, gate, min_speaker_sec, loop, queue)
    try:
        while True:
            event = await queue.get()
            if event is None:
                break
            await pub.publish(event)
    finally:
        server_task.cancel()
        await producer
