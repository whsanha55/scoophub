# core/notify/provisioner.py
"""AutoTopicProvisioner — 라우트 부재 시 자동 토픽 생성 보장 (lazy 자동생성).

사용:
    AutoTopicProvisioner(db).ensure_route(category, detail)  # router.dispatch 직전 1회

흐름:
  매칭 라우트(router._lookup 동일 조건) 有 → 반환.
  無 → LLM 한국어 이름 → Telegram createForumTopic → notify_routes INSERT (purpose='' category 통합).

가드:
  TELEGRAM_DEFAULT_CHAT_ID 미설정 → 자동생성 無 (임의 category 폭증 방지).
  LLM 실패/미설정 → raw 폴백(category 그대로, 📢) 로 createForumTopic. 발신 유지(ON 정책).
  ON CONFLICT DO NOTHING → 동시 크롤 중복 INSERT 방지.
"""
from __future__ import annotations

import json
import logging
from typing import TYPE_CHECKING

from app.config import settings

if TYPE_CHECKING:
    from app.core.database import Database
    from app.core.llm import LLMClient
    from app.core.notify.notifier import Notifier

logger = logging.getLogger(__name__)

_RAW_EMOJI = "📢"
_SYSTEM_PROMPT = (
    "크롤 카테고리명을 한국어 토픽 이름(명사구, 10자 이내)과 이모지 1개로 변환한다. "
    'JSON {"name": "...", "emoji": "..."} 형태로만 출력한다.'
)


class AutoTopicProvisioner:
    def __init__(
        self,
        db: "Database",
        llm: "LLMClient | None" = None,
        notifier: "Notifier | None" = None,
    ) -> None:
        self.db = db
        # lazy — 라우트 hit(대다수) 시 httpx client 열지 않음 (누수 방지). 테스트는 mock 주입.
        self._llm = llm
        self._notifier = notifier

    async def ensure_route(self, category: str, detail: str = "") -> None:
        # 폭증 가드: 기본 chat_id 미설정 → 자동생성 無.
        chat_id = settings.TELEGRAM_DEFAULT_CHAT_ID
        if not chat_id:
            return

        # 매칭 라우트 有 → 반환 (router._lookup 동일 조건. purpose 반영 — F1).
        if await self.db.fetchval(
            "SELECT 1 FROM notify_routes "
            "WHERE enabled AND (category=$1 OR category='') AND (purpose=$2 OR purpose='') "
            "LIMIT 1",
            category,
            detail or "",
        ):
            return

        name, emoji = await self._topic_name(category)
        # create_topic 실패는 그대로 전파 → 호출부(T3) try/except 로 크롤 보호.
        topic_id = await self._create_topic(chat_id, f"{emoji} {name}")
        await self.db.execute(
            "INSERT INTO notify_routes "
            "(category, purpose, channel, chat_id, topic_id, topic_name, enabled) "
            "VALUES ($1, '', 'telegram', $2, $3, $4, TRUE) "
            "ON CONFLICT (category, purpose, channel) DO NOTHING",
            category,
            chat_id,
            topic_id,
            name,
        )

    async def _topic_name(self, category: str) -> tuple[str, str]:
        """LLM 한국어 (name, emoji). 실패/미설정 → raw 폴백(category, 📢). 발신 유지."""
        if self._llm is not None:
            return await self._named_or_raw(self._llm, category)
        # lazy: 주입 없으면 LLMClient 생성 → 사용 후 close (httpx 누수 방지).
        try:
            from app.core.llm import LLMClient

            async with LLMClient() as llm:
                return await self._named_or_raw(llm, category)
        except Exception as e:
            logger.info("auto provision LLM failed, raw fallback (category=%s): %s", category, e)
            return category, _RAW_EMOJI

    async def _named_or_raw(self, llm: "LLMClient", category: str) -> tuple[str, str]:
        try:
            raw = await llm.chat(_SYSTEM_PROMPT, category)
            data = json.loads(raw)
            name = str(data["name"]).strip()
            emoji = str(data.get("emoji", "")).strip() or _RAW_EMOJI
            if not name:
                raise ValueError("empty name")
            return name, emoji
        except Exception as e:
            logger.info("auto provision name parse failed, raw fallback (category=%s): %s", category, e)
            return category, _RAW_EMOJI

    async def _create_topic(self, chat_id: str, name: str) -> int:
        if self._notifier is None:
            # lazy. TelegramNotifier 는 단발 httpx (owns=True) 로 누수 無.
            from app.core.notify.telegram import TelegramNotifier

            self._notifier = TelegramNotifier(settings.TELEGRAM_BOT_TOKEN)
        return await self._notifier.create_topic(chat_id, name)
