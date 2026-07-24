from __future__ import annotations

import asyncio
from typing import Protocol

import websockets

from .events import CaptionEvent


class Publisher(Protocol):
    async def serve(self) -> None: ...
    async def publish(self, event: CaptionEvent) -> None: ...


class WsPublisher:
    def __init__(self, host: str, port: int):
        self._host = host
        self._port = port
        self._clients: set = set()

    async def _handler(self, ws):
        self._clients.add(ws)
        try:
            await ws.wait_closed()
        finally:
            self._clients.discard(ws)

    async def serve(self) -> None:
        async with websockets.serve(self._handler, self._host, self._port):
            await asyncio.Future()

    async def publish(self, event: CaptionEvent) -> None:
        if not self._clients:
            return
        payload = event.to_json()
        await asyncio.gather(
            *(self._safe_send(ws, payload) for ws in list(self._clients)),
            return_exceptions=True,
        )

    async def _safe_send(self, ws, payload: str) -> None:
        try:
            await ws.send(payload)
        except Exception:
            self._clients.discard(ws)


def publisher(host: str, port: int) -> Publisher:
    return WsPublisher(host, port)
