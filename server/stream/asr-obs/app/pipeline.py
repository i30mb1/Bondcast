from __future__ import annotations

import asyncio
import logging

from .asr import Asr
from .audio_source import AudioSource
from .events import CaptionEvent
from .publisher import Publisher
from .speaker import HOST, SpeakerGate
from .vad import Vad

log = logging.getLogger("pipeline")


def _produce(source, vad, asr, gate, min_speaker_sec, loop, queue) -> None:
    seq = 0
    open_utt = False
    utt_speaker: str | None = None
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
            speaker = utt_speaker or HOST

            if segment.is_final:
                open_utt = False
                text = text or last_text
                if not text:
                    continue
            elif not text:
                continue

            event = CaptionEvent(
                seq=seq,
                speaker=speaker,
                text=text,
                start=segment.start,
                end=segment.end,
                final=segment.is_final,
            )
            asyncio.run_coroutine_threadsafe(queue.put(event), loop)
    finally:
        source.close()
        asyncio.run_coroutine_threadsafe(queue.put(None), loop)


async def pipeline(source: AudioSource, vad: Vad, asr: Asr, gate: SpeakerGate, pub: Publisher, min_speaker_sec: float) -> None:
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
