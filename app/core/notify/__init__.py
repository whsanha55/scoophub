# core/notify/__init__.py
"""Notify 서브시스템 — 크롤 완료 발신.

사용:
    from app.core.notify import fire_and_forget_crawl
    fire_and_forget_crawl(db, "news", "rss", result)  # 크롤 블록 X

발신 흐름:
    크롤 결과(CrawlResult) → format_card → NotifyRouter.dispatch → telegram topic.
    토큰 미설정 / 신규 0건 → 조용히 스킵 (크롤 정상 유지).
"""
from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from app.config import settings
from app.core.notify.notifier import Notifier, NotifyMessage
from app.core.notify.router import NotifyRouter
from app.core.notify.telegram import TelegramNotifier

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)

# 큰 섹터(category)별 토픽 이모지 — format_card 표식
_EMOJI = {
    "news": "📰",
    "weather": "🌤",
    "stock": "📈",
    "community": "👥",
    "feed": "📜",
    "kal_bonus": "✈️",
}


def format_card(category: str, detail: str, items_new: int, items_fetched: int = 0) -> str:
    head = f"{_EMOJI.get(category, '🔔')} [{category}"
    if detail:
        head += f" · {detail}"
    head += "]"
    body = f"신규 {items_new}건"
    if items_fetched:
        body += f" (총 {items_fetched}건)"
    return f"{head}\n{body}"


async def dispatch_crawl_notify(
    db: "Database",
    category: str,
    detail: str,
    result: object,
    *,
    payload_key: str | None = None,
) -> None:
    """크롤 결과 발신.

    - 토큰 미설정 → 스킵
    - result None / items_new<=0 → 스킵 (신규 없음)
    - payload_key 미지정 → "{category}:{detail}:{max(new_ids) or 0}" (같은 new set 재발신 방지)
    """
    if not settings.TELEGRAM_BOT_TOKEN:
        return
    if result is None:
        return
    items_new = int(getattr(result, "items_new", 0) or 0)
    if items_new <= 0:
        return

    items_fetched = int(getattr(result, "items_fetched", 0) or 0)
    new_ids = list(getattr(result, "new_article_ids", None) or [])
    if payload_key is None:
        # new_ids 있으면 최대값, 없으면 items_new 로 식별 — 같은 결과 재크롤 시 dedup
        suffix = str(max(new_ids)) if new_ids else f"n{items_new}"
        payload_key = f"{category}:{detail or ''}:{suffix}"

    text = format_card(category, detail, items_new, items_fetched)
    # news: feed_news 에 이미 저장된 LLM summary 재활용 — 탑5 제목+요약 부착 (재호출 비용 0).
    # 실패/요약 없음 → raw 카드 그대로 (degrade).
    if category == "news" and new_ids:
        try:
            text = await _enrich_news(db, text, new_ids)
        except Exception as e:
            logger.warning("news enrich failed (category=news): %s", e)
    router = NotifyRouter(db)
    await router.dispatch(category, detail or "", payload_key, NotifyMessage(text=text))


async def _enrich_news(db: "Database", text: str, new_ids: list[int]) -> str:
    """feed_news 신규 id → 중요도 탑5 제목+summary 부착. summary 없으면 제목만."""
    rows = await db.fetch(
        "SELECT title, summary FROM feed_news "
        "WHERE id=ANY($1::int[]) AND summary IS NOT NULL AND summary <> '' "
        "ORDER BY importance DESC NULLS LAST LIMIT 5",
        new_ids,
    )
    for r in rows:
        title = (r["title"] or "").strip()
        summary = (r["summary"] or "").strip()
        line = f"\n• {title}" if title else ""
        if summary:
            line += f" — {summary[:200]}"
        if line:
            text += line
    return text


def fire_and_forget_crawl(
    db: "Database",
    category: str,
    detail: str,
    result: object,
    *,
    payload_key: str | None = None,
) -> None:
    """크롤 런을 블록하지 않는 비동기 발신. BaseCrawler.run() / scraper 종료점에서 호출."""
    asyncio.create_task(_safe_dispatch(db, category, detail, result, payload_key))


async def _safe_dispatch(
    db: "Database", category: str, detail: str, result: object, payload_key: str | None
) -> None:
    try:
        await dispatch_crawl_notify(db, category, detail, result, payload_key=payload_key)
    except Exception as e:
        logger.error(
            "notify dispatch failed (category=%s detail=%s): %s", category, detail, e
        )


__all__ = [
    "Notifier",
    "NotifyMessage",
    "NotifyRouter",
    "TelegramNotifier",
    "dispatch_crawl_notify",
    "fire_and_forget_crawl",
    "format_card",
]
