# core/notify/__init__.py
"""Notify 서브시스템 — 크롤 완료 발신.

사용:
    from app.core.notify import fire_and_forget_crawl
    fire_and_forget_crawl(db, "news", "rss", result)  # 크롤 블록 X

발신 흐름:
    크롤 결과(CrawlResult) → format_card(base 카드) → enrich(카테고리별 본문)
    → NotifyRouter.dispatch → telegram topic.
    토큰 미설정 / 신규 0건 / enrich None → 조용히 스킵 (크롤 정상 유지).

카테고리 정책:
    - news   : importance>=4 만 (발신은 summarizer 이후 — news/scheduler)
    - weather: 매일 KST 17시+ 1회 (state: crawl_data weather/notify_sent/<date>)
    - kal    : 2027 Q1 프레스티지(P) 잔석만
    - stock  : 발신 스킵 (별도 티켓)
    - 그 외  : crawl_data batch 탑5
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

from app.config import settings
from app.core.notify.card import enrich, format_card
from app.core.notify.notifier import Notifier, NotifyMessage
from app.core.notify.provisioner import AutoTopicProvisioner
from app.core.notify.router import NotifyRouter
from app.core.notify.telegram import TelegramNotifier
from app.crawl_data.repo import CrawlDataRepo

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)

# fire-and-forget task 참조 보관용 — GC 로 인한 task 중도 소멸 방지 (done 시 자동 제거).
_background_tasks: set[asyncio.Task] = set()


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
    - stock → 스킵 (별도 티켓)
    - result None / items_new<=0 → 스킵 (신규 없음)
    - weather → 매일 KST 17시+ 1회 (동일 날짜 재발신 방지)
    - enrich None → 스킵 (0건/필터)
    - payload_key 미지정 → "{category}:{detail}:{max(new_ids) or 0}" (같은 new set 재발신 방지)
    """
    if not settings.TELEGRAM_BOT_TOKEN:
        return
    if result is None:
        return
    if category == "stock":  # 보류 — 별도 티켓
        return
    items_new = int(getattr(result, "items_new", 0) or 0)
    if items_new <= 0:
        return

    items_fetched = int(getattr(result, "items_fetched", 0) or 0)
    new_ids = list(getattr(result, "new_article_ids", None) or [])

    # weather: 매일 KST 7시+ 1회 발신 게이트 (아침 날씨).
    today = None
    if category == "weather":
        now_kst = datetime.now(ZoneInfo("Asia/Seoul"))
        if now_kst.hour < 7:
            return
        today = now_kst.strftime("%Y-%m-%d")
        if await CrawlDataRepo(db).get(
            category="weather", purpose="notify_sent", key=today
        ):
            return  # 오늘 이미 발신

    if payload_key is None:
        if new_ids:
            # new_article_ids 있으면 최대값으로 식별 — 같은 결과 재크롤 시 dedup
            payload_key = f"{category}:{detail or ''}:{max(new_ids)}"
        else:
            # 스냅샷 도메인은 안정 식별키 없음 — 매 run 발신 (빈 키 → dedup 미적용)
            payload_key = ""

    text = format_card(category, detail, items_new, items_fetched)
    enriched = await enrich(db, category, detail or "", text, new_ids)
    if enriched is None:
        return  # 0건/필터 스킵
    text = enriched

    # 라우트 부재 시 자동 토픽 생성 보장 (신규 category). 실패해도 발신/크롤은 계속.
    try:
        await AutoTopicProvisioner(db).ensure_route(category, detail or "")
    except Exception as e:
        logger.warning("auto provision failed (category=%s detail=%s): %s", category, detail, e)
    await NotifyRouter(db).dispatch(
        category, detail or "", payload_key, NotifyMessage(text=text)
    )

    # weather: 발신 성공 후 state upsert — 다음 run 스킵. 실패 시 state 미갱신(재시도 허용).
    if category == "weather" and today is not None:
        try:
            await CrawlDataRepo(db).upsert(
                category="weather",
                purpose="notify_sent",
                key=today,
                response={},
                date_at=datetime.now(timezone.utc),
            )
        except Exception as e:
            logger.warning("weather notify_sent upsert failed: %s", e)


def fire_and_forget_crawl(
    db: "Database",
    category: str,
    detail: str,
    result: object,
    *,
    payload_key: str | None = None,
) -> None:
    """크롤 런을 블록하지 않는 비동기 발신. BaseCrawler.run() / scraper 종료점에서 호출."""
    # 참조 보관 — 버리면 event loop 가 task 를 GC 해 발신이 조용히 누락됨 (CPython 공식 경고).
    task = asyncio.create_task(_safe_dispatch(db, category, detail, result, payload_key))
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)


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
