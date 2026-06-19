# core/notify/notifier.py
"""발신 채널 추상.

신규 채널(Discord/Email)은 Notifier 를 구현하는 클래스 1개 추가.
라우팅은 notify_routes.channel 값으로 분기 — see NotifyRouter.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class NotifyMessage:
    """발신 단위 메시지. text 는 plain (Telegram parse_mode 미사용 — 특수문자 안전)."""

    text: str


class Notifier(ABC):
    """발신 채널 인터페이스."""

    channel: str = "base"

    @abstractmethod
    async def send(self, chat_id: str, topic_id: int | None, message: NotifyMessage) -> None:
        """chat_id 의 topic_id(포럼 토픽, None=일반) 로 message 전송."""

    @abstractmethod
    async def create_topic(self, chat_id: str, name: str) -> int:
        """새 포럼 토픽 생성 → message_thread_id 반환."""
