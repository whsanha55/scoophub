# core/notify/telegram.py
"""Telegram Bot API 발신.

sendMessage (message_thread_id=topic_id) + createForumTopic.
httpx client 는 테스트 주입 가능(transport mock). 미주입 시 send 마다 단발 생성 —
발신이 크롤 주기(분~시간 단위)라 풀 유지 비용 < 단순성.
"""
from __future__ import annotations

import logging

import httpx

from app.core.notify.notifier import NotifyMessage, Notifier

logger = logging.getLogger(__name__)

_API_BASE = "https://api.telegram.org/bot{token}/{method}"
_TIMEOUT = 10.0
# Telegram sendMessage 한도. 초과 시 분할 발신.
_MAX_TEXT = 4096


class TelegramNotifier(Notifier):
    channel = "telegram"

    def __init__(self, token: str, client: httpx.AsyncClient | None = None) -> None:
        self._token = token
        # client=None → send/create_topic 내부에서 단발 생성. 테스트는 mock client 주입.
        self._client = client

    async def send(self, chat_id: str, topic_id: int | None, message: NotifyMessage) -> None:
        for chunk in _split(message.text, _MAX_TEXT):
            payload: dict[str, object] = {"chat_id": chat_id, "text": chunk}
            if topic_id is not None:
                payload["message_thread_id"] = topic_id
            await self._post("sendMessage", payload)

    async def create_topic(self, chat_id: str, name: str) -> int:
        data = await self._post("createForumTopic", {"chat_id": chat_id, "name": name})
        return int(data["result"]["message_thread_id"])

    async def _post(self, method: str, payload: dict[str, object]) -> dict:
        url = _API_BASE.format(token=self._token, method=method)
        owns = self._client is None
        client = self._client or httpx.AsyncClient()
        try:
            resp = await client.post(url, json=payload, timeout=_TIMEOUT)
            resp.raise_for_status()
            return resp.json()
        finally:
            if owns:
                await client.aclose()


def _split(text: str, max_len: int) -> list[str]:
    if len(text) <= max_len:
        return [text]
    return [text[i : i + max_len] for i in range(0, len(text), max_len)]
